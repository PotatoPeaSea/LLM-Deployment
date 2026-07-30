import React, {useState} from 'react';
import {Image, Platform, Pressable, StyleSheet, Text, View} from 'react-native';
import {radius, space, useTheme} from '../theme';
import type {Message, ToolCall} from '../store';

/** Tool name → what to call it in the "used X" line under a reply. */
const TOOL_LABELS: Record<string, string> = {
  web_search: 'web search',
  search_contacts: 'contacts',
  query_calendar: 'calendar',
  get_battery: 'battery',
  get_datetime: 'clock',
  get_device_info: 'device info',
};

/**
 * How much of a tool's output to show.
 *
 * A web search returns several hundred words; the disclosure is for checking
 * what the model was working from, not for reading the page. The model saw all
 * of it either way — this cap is on the display only.
 */
const MAX_RESULT_CHARS = 700;

/** Terminal-ish, because these are arguments and output, not prose. */
const MONO = Platform.select({ios: 'Menlo', android: 'monospace', default: 'monospace'});

/**
 * The model's arguments, tidied for display. Reprinted through JSON so odd
 * spacing collapses, but shown verbatim when it will not parse — malformed
 * arguments are exactly the case this disclosure exists to make visible.
 */
function formatArgs(raw: string): string {
  const text = (raw ?? '').trim();
  if (!text || text === '{}') {
    return '(no arguments)';
  }
  try {
    return JSON.stringify(JSON.parse(text));
  } catch {
    return text;
  }
}

function clip(text: string): string {
  const flat = text.trim();
  return flat.length > MAX_RESULT_CHARS ? `${flat.slice(0, MAX_RESULT_CHARS)}…` : flat;
}

/**
 * One turn. The assistant's reasoning and its tool calls, when there are any,
 * sit above the answer behind disclosures: visible enough to audit, quiet
 * enough to ignore.
 */
export function Bubble({
  message,
  streaming,
}: {
  message: Message;
  streaming?: boolean;
}) {
  const t = useTheme();
  const [open, setOpen] = useState(false);
  const [openTools, setOpenTools] = useState(false);
  const isUser = message.role === 'user';
  const calls: ToolCall[] = message.toolCalls ?? [];
  const running = calls.some(c => c.ms == null);

  return (
    <View style={[styles.row, isUser ? styles.rowUser : styles.rowAssistant]}>
      {!isUser && (!!message.thoughts || calls.length > 0) && (
        <View style={styles.toggles}>
          {!!message.thoughts && (
            <Pressable
              onPress={() => setOpen(v => !v)}
              style={[styles.toggle, {borderColor: t.border}]}
              hitSlop={6}>
              <Text style={[styles.toggleLabel, {color: t.textDim}]}>
                {open ? '▾' : '▸'} Thoughts
              </Text>
            </Pressable>
          )}
          {calls.length > 0 && (
            <Pressable
              onPress={() => setOpenTools(v => !v)}
              style={[styles.toggle, {borderColor: t.border}]}
              hitSlop={6}>
              <Text style={[styles.toggleLabel, {color: t.textDim}]}>
                {openTools ? '▾' : '▸'}{' '}
                {calls.length === 1 ? '1 tool call' : `${calls.length} tool calls`}
                {running ? ' · running' : ''}
              </Text>
            </Pressable>
          )}
        </View>
      )}

      {!isUser && open && !!message.thoughts && (
        <View style={[styles.thoughts, {backgroundColor: t.surfaceAlt, borderColor: t.border}]}>
          <Text style={[styles.thoughtsText, {color: t.textDim}]}>
            {message.thoughts}
          </Text>
        </View>
      )}

      {!isUser && openTools && calls.length > 0 && (
        <View style={[styles.tools, {backgroundColor: t.surfaceAlt, borderColor: t.border}]}>
          {calls.map((call, index) => (
            <View
              key={`${call.name}-${index}`}
              style={index > 0 ? [styles.call, {borderTopColor: t.border}] : undefined}>
              <View style={styles.callHead}>
                <Text style={[styles.callName, {color: t.text}]}>{call.name}</Text>
                <Text style={[styles.callTime, {color: t.textFaint}]}>
                  {call.ms == null ? 'running…' : `${(call.ms / 1000).toFixed(1)}s`}
                </Text>
              </View>
              <Text style={[styles.code, {color: t.textDim}]} selectable>
                {formatArgs(call.arguments)}
              </Text>
              {!!call.result && (
                <Text
                  style={[styles.code, {color: call.ok === false ? t.danger : t.textDim}]}
                  selectable>
                  {`→ ${clip(call.result)}`}
                </Text>
              )}
            </View>
          ))}
        </View>
      )}

      {!!message.images?.length && (
        <View style={styles.images}>
          {message.images.map(path => (
            <Image
              key={path}
              // The picker wrote a real file, so a file:// URI is all Image
              // needs — no permission and no content resolver involved.
              source={{uri: `file://${path}`}}
              style={[styles.image, {borderColor: t.border}]}
            />
          ))}
        </View>
      )}

      <View
        style={[
          styles.bubble,
          isUser
            ? {backgroundColor: t.accent, borderTopRightRadius: radius.sm}
            : {backgroundColor: t.surface, borderTopLeftRadius: radius.sm},
        ]}>
        <Text style={[styles.text, {color: isUser ? t.onAccent : t.text}]}>
          {/* An empty streaming bubble is the gap before the first token off
              the NPU; show something so the UI never looks stuck. */}
          {message.content || (streaming ? '…' : '')}
        </Text>
      </View>

      {!isUser && (message.elapsedMs != null || !!message.toolsUsed?.length) && (
        <Text style={[styles.meta, {color: t.textFaint}]}>
          {[
            message.elapsedMs != null
              ? `${(message.elapsedMs / 1000).toFixed(1)}s`
              : null,
            message.toolsUsed?.length
              ? `used ${[...new Set(message.toolsUsed)]
                  .map(name => TOOL_LABELS[name] ?? name)
                  .join(', ')}`
              : null,
          ]
            .filter(Boolean)
            .join(' · ')}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {marginBottom: space.lg, maxWidth: '86%'},
  rowUser: {alignSelf: 'flex-end', alignItems: 'flex-end'},
  rowAssistant: {alignSelf: 'flex-start', alignItems: 'flex-start'},
  bubble: {
    paddingHorizontal: space.md + 2,
    paddingVertical: space.md,
    borderRadius: radius.lg,
  },
  text: {fontSize: 15.5, lineHeight: 22},
  images: {flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, marginBottom: space.xs},
  image: {
    width: 132,
    height: 132,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    resizeMode: 'cover',
  },
  toggles: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: space.xs,
    marginBottom: space.xs,
  },
  toggle: {
    paddingVertical: space.xs,
    paddingHorizontal: space.sm,
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
  },
  toggleLabel: {fontSize: 12, letterSpacing: 0.2},
  thoughts: {
    padding: space.md,
    marginBottom: space.sm,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
  },
  thoughtsText: {fontSize: 13, lineHeight: 19, fontStyle: 'italic'},
  tools: {
    padding: space.md,
    marginBottom: space.sm,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    gap: space.sm,
  },
  call: {
    paddingTop: space.sm,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  callHead: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: space.sm,
    marginBottom: 2,
  },
  callName: {flex: 1, fontSize: 12.5, fontWeight: '600', fontFamily: MONO},
  callTime: {fontSize: 11},
  code: {fontSize: 11.5, lineHeight: 17, fontFamily: MONO},
  meta: {fontSize: 11, marginTop: space.xs, marginLeft: space.xs},
});
