/**
 * One accent, a grey ramp, and nothing else.
 *
 * The app is a text surface on a devkit screen: colour is used to separate the
 * user's words from the model's, and to mark the one destructive action. Every
 * other distinction is made with weight and space.
 */
import {useColorScheme} from 'react-native';

export type Theme = ReturnType<typeof useTheme>;

const light = {
  bg: '#FFFFFF',
  surface: '#F4F4F5',
  surfaceAlt: '#FAFAFA',
  border: '#E4E4E7',
  text: '#18181B',
  textDim: '#71717A',
  textFaint: '#A1A1AA',
  accent: '#2563EB',
  onAccent: '#FFFFFF',
  danger: '#DC2626',
};

const dark = {
  bg: '#0B0B0D',
  surface: '#18181B',
  surfaceAlt: '#141417',
  border: '#27272A',
  text: '#FAFAFA',
  textDim: '#A1A1AA',
  textFaint: '#71717A',
  accent: '#3B82F6',
  onAccent: '#FFFFFF',
  danger: '#EF4444',
};

export const space = {xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32};

export const radius = {sm: 8, md: 12, lg: 18, pill: 999};

export function useTheme() {
  const scheme = useColorScheme();
  const c = scheme === 'dark' ? dark : light;
  return {...c, isDark: scheme === 'dark'};
}

/** "2m", "3h", "yesterday" — chat lists don't need timestamps to the second. */
export function relativeTime(ts: number): string {
  const seconds = Math.floor((Date.now() - ts) / 1000);
  if (seconds < 60) {
    return 'now';
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)}m`;
  }
  if (seconds < 86400) {
    return `${Math.floor(seconds / 3600)}h`;
  }
  if (seconds < 172800) {
    return 'yesterday';
  }
  return `${Math.floor(seconds / 86400)}d`;
}
