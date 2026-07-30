import React, {useState} from 'react';
import {
  Image,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
  type NativeSyntheticEvent,
  type TextInputKeyPressEventData,
} from 'react-native';
import {radius, space, useTheme} from '../theme';
import {pickImage} from '../genie';

/**
 * A turn the user has written but the model cannot start yet.
 *
 * Ephemeral, and deliberately not in `store.ts`: a queue that survived a reload
 * would fire messages into a chat the user has stopped looking at.
 */
export type QueuedTurn = {
  id: string;
  /** Which chat it belongs to — the queue outlives a chat switch, entries don't. */
  chatId: string;
  text: string;
  images: string[];
};

/**
 * Input row.
 *
 * Enter sends, Shift+Enter makes a newline — web only. On a phone the return
 * key is how you get a second line and there is no modifier to hold, so the
 * Android build keeps Enter as a newline and the button as the only send.
 *
 * Typing is never blocked. While a reply is generating (or a model is still
 * loading) a submit becomes a *queued* turn instead of a rejected one: a reply
 * here can run for tens of seconds, and losing the follow-up you thought of
 * during it is worse than waiting for it. The parent decides send-vs-queue —
 * this component just reports the submit and renders what is waiting.
 *
 * The attach button only exists for a model that can actually see: offering it
 * for the text-only QNN models would produce an attachment the model silently
 * ignores, which reads as a bug.
 */
export function Composer({
  busy,
  ready,
  disabled,
  canAttach,
  queued = [],
  onSend,
  onStop,
  onUnqueue,
}: {
  /** A reply is being generated. */
  busy: boolean;
  /** The model is resident and can start a turn now. */
  ready: boolean;
  /** Nothing can be sent at all — the model failed to load. */
  disabled?: boolean;
  canAttach?: boolean;
  /** Turns waiting for the model, oldest first. */
  queued?: QueuedTurn[];
  onSend: (text: string, imagePaths: string[]) => void;
  onStop: () => void;
  onUnqueue?: (id: string) => void;
}) {
  const t = useTheme();
  const [text, setText] = useState('');
  const [images, setImages] = useState<string[]>([]);

  // What a submit will do right now. The button says which, so "Send" never
  // sits there while the answer it would start is minutes away.
  const willQueue = busy || !ready;

  const submit = () => {
    const trimmed = text.trim();
    // An image on its own is a valid turn — the model is asked to describe it.
    if ((!trimmed && images.length === 0) || disabled) {
      return;
    }
    setText('');
    setImages([]);
    onSend(trimmed, images);
  };

  /**
   * Enter to send on web.
   *
   * react-native-web only calls `onSubmitEditing` for a single-line input, so a
   * multiline box has to read the key itself — and must `preventDefault` to stop
   * the same Enter from also inserting the newline it was pressed instead of.
   */
  const onKeyPress = (event: NativeSyntheticEvent<TextInputKeyPressEventData>) => {
    if (Platform.OS !== 'web') {
      return;
    }
    // On web this is the React keydown event: `key` and `shiftKey` sit on it
    // directly, and its `nativeEvent` is the DOM event.
    const e = event as unknown as {
      key?: string;
      shiftKey?: boolean;
      preventDefault?: () => void;
      nativeEvent?: {key?: string; shiftKey?: boolean};
    };
    const key = e.key ?? e.nativeEvent?.key;
    const shift = e.shiftKey ?? e.nativeEvent?.shiftKey;
    if (key === 'Enter' && !shift) {
      e.preventDefault?.();
      submit();
    }
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

  const canSubmit = (!!text.trim() || images.length > 0) && !disabled;

  const queueNote =
    `${queued.length === 1 ? 'Queued' : `${queued.length} queued`} · sends ` +
    `${busy ? 'when this reply finishes' : 'once the model is ready'} · tap to remove`;

  const placeholder = disabled
    ? 'Model not loaded'
    : busy
    ? 'Queue a follow-up…'
    : !ready
    ? 'Queue a message…'
    : 'Message';

  return (
    <View style={[styles.wrap, {borderTopColor: t.border, backgroundColor: t.bg}]}>
      {queued.length > 0 && (
        <View style={styles.queue}>
          {queued.map(item => (
            <Pressable
              key={item.id}
              onPress={() => onUnqueue?.(item.id)}
              style={[styles.chip, {backgroundColor: t.surfaceAlt, borderColor: t.border}]}>
              <Text numberOfLines={1} style={[styles.chipText, {color: t.textDim}]}>
                {item.text || 'Image'}
              </Text>
              <Text style={[styles.chipX, {color: t.textFaint}]}>×</Text>
            </Pressable>
          ))}
          <Text style={[styles.queueNote, {color: t.textFaint}]}>{queueNote}</Text>
        </View>
      )}

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
          placeholder={placeholder}
          placeholderTextColor={t.textFaint}
          value={text}
          onChangeText={setText}
          onKeyPress={onKeyPress}
          editable={!disabled}
          multiline
        />
        {busy && (
          <Pressable
            onPress={onStop}
            style={[styles.button, {backgroundColor: t.danger}]}>
            <Text style={[styles.buttonText, {color: t.onAccent}]}>Stop</Text>
          </Pressable>
        )}
        {/* While a reply runs, Stop is the whole button row until there is
            something to queue — an empty box next to it would just be clutter. */}
        {(!busy || canSubmit) && (
          <Pressable
            onPress={submit}
            disabled={!canSubmit}
            style={[
              styles.button,
              {backgroundColor: t.accent, opacity: canSubmit ? 1 : 0.35},
            ]}>
            <Text style={[styles.buttonText, {color: t.onAccent}]}>
              {willQueue ? 'Queue' : 'Send'}
            </Text>
          </Pressable>
        )}
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
  queue: {flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, marginBottom: space.sm},
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.xs,
    maxWidth: '100%',
    paddingVertical: space.xs,
    paddingHorizontal: space.sm,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
  },
  chipText: {flexShrink: 1, fontSize: 12.5},
  chipX: {fontSize: 13, fontWeight: '700'},
  queueNote: {width: '100%', fontSize: 11, marginTop: 2},
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
