import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  FlatList,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {Bubble} from '../components/Bubble';
import {Composer} from '../components/Composer';
import {Sheet, ToggleRow} from '../components/Sheet';
import {space, useTheme} from '../theme';
import {generate, loadModel, stop, type ModelInfo} from '../genie';
import {deriveTitle, newId, toWire, type Chat, type Message, type Settings} from '../store';

type LoadState =
  | {kind: 'loading'}
  | {kind: 'staging'; percent: number}
  | {kind: 'ready'; contextLength: number; loadMs: number}
  | {kind: 'error'; message: string};

export function ChatScreen({
  chat,
  models,
  settings,
  onBack,
  onChange,
  onSettings,
}: {
  chat: Chat;
  models: ModelInfo[];
  settings: Settings;
  onBack: () => void;
  onChange: (chat: Chat) => void;
  onSettings: (patch: Partial<Settings>) => void;
}) {
  const t = useTheme();
  const [load, setLoad] = useState<LoadState>({kind: 'loading'});
  const [busy, setBusy] = useState(false);
  const [sheet, setSheet] = useState(false);
  const [ctx, setCtx] = useState<{used: number; total: number} | null>(null);
  const [capped, setCapped] = useState(false);
  const listRef = useRef<FlatList<Message>>(null);

  const model = models.find(m => m.id === chat.modelId);

  // A chat is pinned to its model, so opening one may swap what is resident on
  // the NPU. loadModel no-ops when it is already the loaded model.
  useEffect(() => {
    let cancelled = false;
    setLoad({kind: 'loading'});
    setCtx(null);
    loadModel(chat.modelId, percent => {
      if (!cancelled) {
        setLoad({kind: 'staging', percent});
      }
    })
      .then(info => {
        if (!cancelled) {
          setLoad({kind: 'ready', contextLength: info.contextLength, loadMs: info.loadMs});
        }
      })
      .catch(e => {
        if (!cancelled) {
          setLoad({kind: 'error', message: String(e?.message ?? e)});
        }
      });
    return () => {
      cancelled = true;
    };
  }, [chat.modelId]);

  const send = useCallback(
    async (text: string) => {
      const history = chat.messages;
      const userMessage: Message = {id: newId(), role: 'user', content: text};
      const draft: Message = {id: newId(), role: 'assistant', content: ''};

      let working: Chat = {
        ...chat,
        title: history.length === 0 ? deriveTitle(text) : chat.title,
        messages: [...history, userMessage, draft],
        updatedAt: Date.now(),
      };
      onChange(working);
      setBusy(true);
      setCapped(false);

      const patchDraft = (patch: Partial<Message>) => {
        working = {
          ...working,
          messages: working.messages.map(m =>
            m.id === draft.id ? {...m, ...patch} : m,
          ),
          updatedAt: Date.now(),
        };
        onChange(working);
      };

      try {
        const result = await generate(
          {
            chatId: chat.id,
            modelId: chat.modelId,
            // History BEFORE this turn: native replays it only when the KV
            // cache has to be rebuilt, but it must always be accurate.
            history: toWire(history),
            text,
            brevity: settings.brevity,
            thinking: settings.thinking,
          },
          progress =>
            patchDraft({content: progress.answer, thoughts: progress.thoughts || undefined}),
        );
        patchDraft({
          content: result.answer,
          thoughts: result.thoughts || undefined,
          elapsedMs: result.elapsedMs,
        });
        setCtx({used: result.contextUsed, total: result.contextLength});
        setCapped(result.capped);
      } catch (e: any) {
        patchDraft({content: `⚠︎ ${e?.message ?? e}`});
      } finally {
        setBusy(false);
      }
    },
    [chat, onChange, settings.brevity, settings.thinking],
  );

  const status =
    load.kind === 'loading'
      ? 'Loading model onto the NPU…'
      : load.kind === 'staging'
      ? `Copying model to internal storage — ${load.percent}%`
      : load.kind === 'error'
      ? load.message
      : busy
      ? 'Generating…'
      : ctx
      ? `${model?.name ?? chat.modelId} · context ${ctx.used}/${ctx.total}` +
        (capped ? ' · reply stopped at the length limit' : '') +
        (ctx.used / ctx.total > 0.75 ? ' · older turns will be trimmed soon' : '')
      : `${model?.name ?? chat.modelId} · ${load.contextLength} ctx · loaded in ${(
          load.loadMs / 1000
        ).toFixed(1)}s`;

  return (
    <KeyboardAvoidingView
      style={[styles.root, {backgroundColor: t.bg}]}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <View style={[styles.header, {borderBottomColor: t.border}]}>
        <Pressable onPress={onBack} hitSlop={12}>
          <Text style={[styles.back, {color: t.accent}]}>‹ Chats</Text>
        </Pressable>
        <Text numberOfLines={1} style={[styles.title, {color: t.text}]}>
          {chat.title}
        </Text>
        <Pressable onPress={() => setSheet(true)} hitSlop={12}>
          <Text style={[styles.gear, {color: t.textDim}]}>•••</Text>
        </Pressable>
      </View>

      <FlatList
        ref={listRef}
        data={chat.messages}
        keyExtractor={m => m.id}
        contentContainerStyle={styles.list}
        renderItem={({item, index}) => (
          <Bubble
            message={item}
            streaming={busy && index === chat.messages.length - 1}
          />
        )}
        onContentSizeChange={() => listRef.current?.scrollToEnd({animated: true})}
      />

      <View style={styles.statusRow}>
        {(load.kind === 'loading' || load.kind === 'staging') && (
          <ActivityIndicator size="small" color={t.textDim} />
        )}
        <Text
          numberOfLines={2}
          style={[styles.status, {color: load.kind === 'error' ? t.danger : t.textFaint}]}>
          {status}
        </Text>
      </View>

      <Composer
        busy={busy}
        disabled={load.kind !== 'ready'}
        onSend={send}
        onStop={stop}
      />

      <Sheet visible={sheet} title="This chat" onClose={() => setSheet(false)}>
        <ToggleRow
          label="Brief answers"
          hint="Asks for one or two short sentences. Off lets the model run long."
          value={settings.brevity}
          onChange={v => onSettings({brevity: v})}
        />
        <ToggleRow
          label="Reasoning"
          hint={
            model?.supportsReasoning
              ? 'Qwen3 thinks before answering. Slower, and it spends context.'
              : `${model?.name ?? 'This model'} has no reasoning mode.`
          }
          value={settings.thinking && !!model?.supportsReasoning}
          disabled={!model?.supportsReasoning}
          onChange={v => onSettings({thinking: v})}
        />
        <Text style={[styles.sheetNote, {color: t.textFaint}]}>
          The model is fixed per chat — start a new chat to use a different one.
        </Text>
      </Sheet>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    paddingHorizontal: space.lg,
    paddingVertical: space.md,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  back: {fontSize: 16},
  title: {flex: 1, fontSize: 16, fontWeight: '600', textAlign: 'center'},
  gear: {fontSize: 16, letterSpacing: 1},
  list: {padding: space.lg, paddingBottom: space.sm},
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    paddingHorizontal: space.lg,
    paddingBottom: space.xs,
  },
  status: {flex: 1, fontSize: 11.5},
  sheetNote: {fontSize: 12, marginTop: space.md, lineHeight: 17},
});
