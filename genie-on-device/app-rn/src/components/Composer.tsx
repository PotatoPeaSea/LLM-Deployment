import React, {useState} from 'react';
import {Image, Pressable, StyleSheet, Text, TextInput, View} from 'react-native';
import {radius, space, useTheme} from '../theme';
import {pickImage} from '../genie';

/**
 * Input row. The send button becomes stop while a reply is generating —
 * a query can run for many seconds and the user needs a way out of a bad one.
 *
 * The attach button only exists for a model that can actually see: offering it
 * for the text-only QNN models would produce an attachment the model silently
 * ignores, which reads as a bug.
 */
export function Composer({
  busy,
  disabled,
  canAttach,
  onSend,
  onStop,
}: {
  busy: boolean;
  disabled?: boolean;
  canAttach?: boolean;
  onSend: (text: string, imagePaths: string[]) => void;
  onStop: () => void;
}) {
  const t = useTheme();
  const [text, setText] = useState('');
  const [images, setImages] = useState<string[]>([]);

  const submit = () => {
    const trimmed = text.trim();
    // An image on its own is a valid turn — the model is asked to describe it.
    if ((!trimmed && images.length === 0) || busy || disabled) {
      return;
    }
    setText('');
    setImages([]);
    onSend(trimmed, images);
  };

  const attach = async () => {
    try {
      const path = await pickImage();
      if (path) {
        setImages(prev => [...prev, path]);
      }
    } catch {
      // A cancelled or unavailable picker is not worth interrupting the user.
    }
  };

  const active = busy || ((!!text.trim() || images.length > 0) && !disabled);

  return (
    <View style={[styles.wrap, {borderTopColor: t.border, backgroundColor: t.bg}]}>
      {images.length > 0 && (
        <View style={styles.tray}>
          {images.map(path => (
            <Pressable
              key={path}
              onPress={() => setImages(prev => prev.filter(p => p !== path))}>
              <Image source={{uri: `file://${path}`}} style={styles.thumb} />
              <View style={[styles.remove, {backgroundColor: t.danger}]}>
                <Text style={[styles.removeText, {color: t.onAccent}]}>×</Text>
              </View>
            </Pressable>
          ))}
        </View>
      )}

      <View style={styles.row}>
        {canAttach && (
          <Pressable
            onPress={attach}
            disabled={busy || disabled}
            style={[
              styles.attach,
              {backgroundColor: t.surface, opacity: busy || disabled ? 0.35 : 1},
            ]}>
            <Text style={[styles.attachText, {color: t.textDim}]}>＋</Text>
          </Pressable>
        )}
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
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    padding: space.sm,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  row: {flexDirection: 'row', alignItems: 'flex-end', gap: space.sm},
  tray: {flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginBottom: space.sm},
  thumb: {width: 64, height: 64, borderRadius: radius.md},
  remove: {
    position: 'absolute',
    top: -4,
    right: -4,
    width: 18,
    height: 18,
    borderRadius: 9,
    alignItems: 'center',
    justifyContent: 'center',
  },
  removeText: {fontSize: 12, lineHeight: 14, fontWeight: '700'},
  attach: {
    height: 44,
    width: 44,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
  },
  attachText: {fontSize: 20, lineHeight: 24},
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
