import React, {useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View} from 'react-native';
import {radius, space, useTheme} from '../theme';
import type {Message} from '../store';

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
 * One turn. The assistant's reasoning, when there is any, sits above the answer
 * behind a disclosure: visible enough to audit, quiet enough to ignore.
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
  const isUser = message.role === 'user';

  return (
    <View style={[styles.row, isUser ? styles.rowUser : styles.rowAssistant]}>
      {!isUser && !!message.thoughts && (
        <Pressable
          onPress={() => setOpen(v => !v)}
          style={[styles.thoughtsToggle, {borderColor: t.border}]}
          hitSlop={6}>
          <Text style={[styles.thoughtsLabel, {color: t.textDim}]}>
            {open ? '▾' : '▸'} Thoughts
          </Text>
        </Pressable>
      )}

      {!isUser && open && !!message.thoughts && (
        <View style={[styles.thoughts, {backgroundColor: t.surfaceAlt, borderColor: t.border}]}>
          <Text style={[styles.thoughtsText, {color: t.textDim}]}>
            {message.thoughts}
          </Text>
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
  thoughtsToggle: {
    paddingVertical: space.xs,
    paddingHorizontal: space.sm,
    marginBottom: space.xs,
    borderRadius: radius.sm,
    borderWidth: StyleSheet.hairlineWidth,
  },
  thoughtsLabel: {fontSize: 12, letterSpacing: 0.2},
  thoughts: {
    padding: space.md,
    marginBottom: space.sm,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
  },
  thoughtsText: {fontSize: 13, lineHeight: 19, fontStyle: 'italic'},
  meta: {fontSize: 11, marginTop: space.xs, marginLeft: space.xs},
});
