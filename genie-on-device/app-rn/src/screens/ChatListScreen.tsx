import React from 'react';
import {Alert, FlatList, Pressable, StyleSheet, Text, View} from 'react-native';
import {relativeTime, space, useTheme} from '../theme';
import type {Chat} from '../store';
import type {ModelInfo} from '../genie';

export function ChatListScreen({
  chats,
  models,
  onOpen,
  onNew,
  onDelete,
}: {
  chats: Chat[];
  models: ModelInfo[];
  onOpen: (chat: Chat) => void;
  onNew: () => void;
  onDelete: (chat: Chat) => void;
}) {
  const t = useTheme();
  const modelName = (id: string) =>
    models.find(m => m.id === id)?.name ?? id;

  return (
    <View style={[styles.root, {backgroundColor: t.bg}]}>
      <View style={styles.header}>
        <Text style={[styles.heading, {color: t.text}]}>Chats</Text>
        <Pressable onPress={onNew} hitSlop={12}>
          <Text style={[styles.new, {color: t.accent}]}>New</Text>
        </Pressable>
      </View>

      <FlatList
        data={chats}
        keyExtractor={c => c.id}
        contentContainerStyle={chats.length === 0 && styles.emptyWrap}
        ItemSeparatorComponent={() => (
          <View style={[styles.separator, {backgroundColor: t.border}]} />
        )}
        ListEmptyComponent={
          <Text style={[styles.empty, {color: t.textDim}]}>
            No chats yet.{'\n'}Everything you type stays on this device.
          </Text>
        }
        renderItem={({item}) => (
          <Pressable
            onPress={() => onOpen(item)}
            onLongPress={() =>
              Alert.alert('Delete chat?', item.title, [
                {text: 'Cancel', style: 'cancel'},
                {text: 'Delete', style: 'destructive', onPress: () => onDelete(item)},
              ])
            }
            style={styles.item}>
            <View style={styles.itemMain}>
              <Text numberOfLines={1} style={[styles.itemTitle, {color: t.text}]}>
                {item.title}
              </Text>
              <Text style={[styles.itemMeta, {color: t.textDim}]}>
                {modelName(item.modelId)} · {item.messages.length} messages
              </Text>
            </View>
            <Text style={[styles.itemTime, {color: t.textFaint}]}>
              {relativeTime(item.updatedAt)}
            </Text>
          </Pressable>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  header: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    paddingHorizontal: space.lg,
    paddingTop: space.lg,
    paddingBottom: space.md,
  },
  heading: {fontSize: 30, fontWeight: '700', letterSpacing: -0.5},
  new: {fontSize: 16, fontWeight: '600'},
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: space.lg,
    paddingVertical: space.md + 2,
    gap: space.md,
  },
  itemMain: {flex: 1},
  itemTitle: {fontSize: 16},
  itemMeta: {fontSize: 12.5, marginTop: 3},
  itemTime: {fontSize: 12},
  separator: {height: StyleSheet.hairlineWidth, marginLeft: space.lg},
  emptyWrap: {flexGrow: 1, justifyContent: 'center'},
  empty: {textAlign: 'center', fontSize: 14, lineHeight: 21},
});
