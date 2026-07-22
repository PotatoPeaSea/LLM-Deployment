import React, {useState} from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {radius, space, useTheme} from '../theme';
import type {Message} from '../store';

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

      {!isUser && message.elapsedMs != null && (
        <Text style={[styles.meta, {color: t.textFaint}]}>
          {(message.elapsedMs / 1000).toFixed(1)}s
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
