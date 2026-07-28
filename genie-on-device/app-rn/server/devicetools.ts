/**
 * The tools that need no network: pure reads of state the box already has.
 *
 * Ported from `DeviceTools.kt`. The schemas and their wording are kept as close
 * to the Android originals as the facts allow, because that wording is what the
 * model is steered by — it is prompt text, not documentation. Only the *sources*
 * changed, from Android APIs to sysfs/procfs:
 *
 *   BatteryManager broadcast  ->  /sys/class/power_supply/{battery,usb}
 *   Build.* + ActivityManager ->  /proc/device-tree/model, /sys/devices/soc0,
 *                                 /etc/os-release, /proc/meminfo, statfs
 *
 * `get_datetime` needed no port at all.
 *
 * Everything here degrades to a sentence rather than throwing: a missing sysfs
 * node on a different board must produce "unavailable", which the model can
 * relay, not an exception that kills the turn.
 */
import {readFileSync, statfsSync} from 'node:fs';
import {functionSchema, noArgs, type Tool} from './tools';

/** Read a file, trimmed, or null if it isn't there. Never throws. */
function read(path: string): string | null {
  try {
    return readFileSync(path, 'utf8').trim();
  } catch {
    return null;
  }
}

/**
 * Parse a `KEY=value` block (sysfs `uevent`, `/etc/os-release`) into a map.
 * Both formats are the same shape; os-release just quotes its values.
 */
function parseKeyValues(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of text.split('\n')) {
    const eq = line.indexOf('=');
    if (eq > 0) {
      out[line.slice(0, eq)] = line.slice(eq + 1).replace(/^"|"$/g, '');
    }
  }
  return out;
}

export const DateTimeTool: Tool = {
  name: 'get_datetime',

  schema: () =>
    functionSchema(
      'get_datetime',
      'Get the current date, time and timezone on this device. Use this for ' +
        'any question about the current date or time, or to compute how far ' +
        'away something is.',
      noArgs(),
    ),

  async run() {
    const now = new Date();
    const stamp = now.toLocaleString(undefined, {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    return `${stamp} (${zone})`;
  },
};

export const BatteryTool: Tool = {
  name: 'get_battery',

  schema: () =>
    functionSchema(
      'get_battery',
      "Get this device's battery level, whether it is charging, and its " +
        'temperature.',
      noArgs(),
    ),

  async run() {
    const uevent = read('/sys/class/power_supply/battery/uevent');
    if (!uevent) {
      return 'Battery status unavailable on this device.';
    }
    const p = parseKeyValues(uevent);

    const capacity = p.POWER_SUPPLY_CAPACITY;
    const percent = capacity ? `${capacity}%` : 'unknown';

    // The kernel's own STATUS string ("Charging"/"Discharging"/"Not charging"/
    // "Full") is more honest than deriving it from usb/online: on a dev board
    // the USB port can be online purely for adb while the charger does nothing,
    // which is exactly the "Not charging" case.
    const status = p.POWER_SUPPLY_STATUS ?? 'unknown';
    const usbOnline = read('/sys/class/power_supply/usb/online') === '1';
    const supply = usbOnline ? 'USB connected' : 'on battery';

    // POWER_SUPPLY_TEMP is in tenths of a degree C, same units Android used.
    const tenths = Number(p.POWER_SUPPLY_TEMP);
    const temp = Number.isFinite(tenths) && tenths > 0 ? `, ${(tenths / 10).toFixed(1)}°C` : '';

    return `Battery ${percent}, ${status.toLowerCase()} (${supply})${temp}.`;
  },
};

export const DeviceInfoTool: Tool = {
  name: 'get_device_info',

  schema: () =>
    functionSchema(
      'get_device_info',
      'Get information about this device: model, system-on-chip, OS version, ' +
        'and free storage and memory.',
      noArgs(),
    ),

  async run() {
    // The device-tree model string is NUL-terminated in sysfs.
    const model = read('/proc/device-tree/model')?.replace(/\0/g, '') ?? 'unknown device';
    const soc = read('/sys/devices/soc0/machine') ?? 'unknown SoC';
    const os = parseKeyValues(read('/etc/os-release') ?? '');
    const osName = os.PRETTY_NAME ?? 'unknown OS';
    const kernel = read('/proc/sys/kernel/osrelease') ?? 'unknown';

    const parts = [`${model}, SoC ${soc}. ${osName}, kernel ${kernel}.`];

    // Storage: report the partition the models actually live on, which on this
    // board is /data (the big one), not /.
    try {
      const fs = statfsSync(process.env.GENIE_MODELS_ROOT ?? '/data');
      const freeGb = (Number(fs.bavail) * Number(fs.bsize)) / 1e9;
      const totalGb = (Number(fs.blocks) * Number(fs.bsize)) / 1e9;
      parts.push(`Storage ${freeGb.toFixed(1)} GB free of ${totalGb.toFixed(1)} GB.`);
    } catch {
      // Non-fatal: the rest of the answer is still useful.
    }

    // MemAvailable, not MemFree: MemFree excludes reclaimable page cache and
    // badly understates what a new model load can actually get.
    const meminfo = read('/proc/meminfo');
    if (meminfo) {
      const kb = (key: string) =>
        Number(new RegExp(`^${key}:\\s+(\\d+) kB`, 'm').exec(meminfo)?.[1] ?? NaN);
      const avail = kb('MemAvailable');
      const total = kb('MemTotal');
      if (Number.isFinite(avail) && Number.isFinite(total)) {
        parts.push(`RAM ${(avail / 1e6).toFixed(1)} GB free of ${(total / 1e6).toFixed(1)} GB.`);
      }
    }

    return parts.join(' ');
  },
};
