/**
 * Genie Chat — Llama 3.2 and Qwen3 on the device's Hexagon NPU.
 *
 * This file owns all durable state (chats, settings) and does the navigating.
 * With two screens and one sheet, a navigation library would be more moving
 * parts than the thing it navigates.
 */
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {SafeAreaView, StatusBar, StyleSheet, Text, View} from 'react-native';
import {ChatListScreen} from './src/screens/ChatListScreen';
import {ChatScreen} from './src/screens/ChatScreen';
import {ModelRow, Sheet} from './src/components/Sheet';
import {listModels, resetConversation, type ModelInfo} from './src/genie';
import {
  DEFAULT_SETTINGS,
  loadChats,
  loadSettings,
  newId,
  saveChats,
  saveSettings,
  type Chat,
  type Settings,
} from './src/store';
import {space, useTheme} from './src/theme';

export default function App() {
  const t = useTheme();
  const [chats, setChats] = useState<Chat[]>([]);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS);
  const [openChatId, setOpenChatId] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    Promise.all([loadChats(), loadSettings(), listModels()])
      .then(([storedChats, storedSettings, availableModels]) => {
        setChats(storedChats);
        setSettings(storedSettings);
        setModels(availableModels);
      })
      .finally(() => setReady(true));
  }, []);

  const persistChats = useCallback((next: Chat[]) => {
    setChats(next);
    void saveChats(next);
  }, []);

  const updateChat = useCallback(
    (chat: Chat) =>
      setChats(prev => {
        const next = prev.map(c => (c.id === chat.id ? chat : c));
        void saveChats(next);
        return next;
      }),
    [],
  );

  const patchSettings = useCallback((patch: Partial<Settings>) => {
    setSettings(prev => {
      const next = {...prev, ...patch};
      void saveSettings(next);
      return next;
    });
  }, []);

  const startChat = useCallback(
    (modelId: string) => {
      const chat: Chat = {
        id: newId(),
        title: 'New chat',
        modelId,
        createdAt: Date.now(),
        updatedAt: Date.now(),
        messages: [],
      };
      persistChats([chat, ...chats]);
      patchSettings({lastModelId: modelId});
      setPicking(false);
      setOpenChatId(chat.id);
    },
    [chats, patchSettings, persistChats],
  );

  const deleteChat = useCallback(
    (chat: Chat) => {
      persistChats(chats.filter(c => c.id !== chat.id));
      // The KV cache may still hold this conversation; don't let it leak into
      // whatever gets opened next.
      void resetConversation();
    },
    [chats, persistChats],
  );

  const openChat = useMemo(
    () => chats.find(c => c.id === openChatId) ?? null,
    [chats, openChatId],
  );

  const installed = models.filter(m => m.installed);

  if (!ready) {
    return <SafeAreaView style={[styles.root, {backgroundColor: t.bg}]} />;
  }

  return (
    <SafeAreaView style={[styles.root, {backgroundColor: t.bg}]}>
      <StatusBar
        barStyle={t.isDark ? 'light-content' : 'dark-content'}
        backgroundColor={t.bg}
      />

      {openChat ? (
        <ChatScreen
          chat={openChat}
          models={models}
          settings={settings}
          onBack={() => setOpenChatId(null)}
          onChange={updateChat}
          onSettings={patchSettings}
        />
      ) : (
        <ChatListScreen
          chats={chats}
          models={models}
          onOpen={chat => setOpenChatId(chat.id)}
          onNew={() => {
            // One model installed is not a choice worth a sheet.
            if (installed.length === 1) {
              startChat(installed[0].id);
            } else {
              setPicking(true);
            }
          }}
          onDelete={deleteChat}
        />
      )}

      <Sheet visible={picking} title="New chat with" onClose={() => setPicking(false)}>
        {models.map(model => (
          <ModelRow
            key={model.id}
            model={model}
            selected={settings.lastModelId === model.id}
            onPress={() => startChat(model.id)}
          />
        ))}
        {installed.length === 0 && (
          <View style={styles.note}>
            <Text style={[styles.noteText, {color: t.textDim}]}>
              No model bundles on this device. Push one from the host:
              {'\n\n'}
              ./scripts/10_push_app_model.sh llama_v3_2_1b_instruct_ctx4096 com.geniechatrn
            </Text>
          </View>
        )}
      </Sheet>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  note: {paddingVertical: space.md},
  noteText: {fontSize: 12.5, lineHeight: 19},
});
