import React, {useState} from 'react';
import {Pressable, StyleSheet, Text, TextInput, View} from 'react-native';
import {radius, space, useTheme} from '../theme';

/**
 * Input row. The send button becomes stop while a reply is generating —
 * a query can run for many seconds and the user needs a way out of a bad one.
 */
export function Composer({
  busy,
  disabled,
  onSend,
  onStop,
}: {
  busy: boolean;
  disabled?: boolean;
  onSend: (text: string) => void;
  onStop: () => void;
}) {
  const t = useTheme();
  const [text, setText] = useState('');

  const submit = () => {
    const trimmed = text.trim();
    if (!trimmed || busy || disabled) {
      return;
    }
    setText('');
    onSend(trimmed);
  };

  const active = busy || (!!text.trim() && !disabled);

  return (
    <View style={[styles.wrap, {borderTopColor: t.border, backgroundColor: t.bg}]}>
      <TextInput
        style={[styles.input, {backgroundColor: t.surface, color: t.text}]}
        placeholder={disabled ? 'Model not loaded' : 'Message'}
        placeholderTextColor={t.textFaint}
        value={text}
        onChangeText={setText}
        editable={!busy && !disabled}
        multiline
      />
      <Pressable
        onPress={busy ? onStop : submit}
        disabled={!active}
        style={[
          styles.button,
          {
            backgroundColor: busy ? t.danger : t.accent,
            opacity: active ? 1 : 0.35,
          },
        ]}>
        <Text style={[styles.buttonText, {color: t.onAccent}]}>
          {busy ? 'Stop' : 'Send'}
        </Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    padding: space.sm,
    gap: space.sm,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  input: {
    flex: 1,
    maxHeight: 120,
    minHeight: 44,
    paddingHorizontal: space.md,
    paddingTop: space.md - 2,
    paddingBottom: space.md - 2,
    borderRadius: radius.lg,
    fontSize: 15.5,
  },
  button: {
    height: 44,
    paddingHorizontal: space.lg,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: {fontSize: 15, fontWeight: '600'},
});
