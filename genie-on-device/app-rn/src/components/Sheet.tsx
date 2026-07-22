import React from 'react';
import {Modal, Pressable, StyleSheet, Switch, Text, View} from 'react-native';
import {radius, space, useTheme} from '../theme';
import type {ModelInfo} from '../genie';

/** Bottom sheet. One Modal, reused for settings and for picking a model. */
export function Sheet({
  visible,
  title,
  onClose,
  children,
}: {
  visible: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  const t = useTheme();
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={styles.scrim} onPress={onClose} />
      <View style={[styles.sheet, {backgroundColor: t.bg, borderColor: t.border}]}>
        <View style={[styles.grabber, {backgroundColor: t.border}]} />
        <Text style={[styles.title, {color: t.text}]}>{title}</Text>
        {children}
      </View>
    </Modal>
  );
}

export function ToggleRow({
  label,
  hint,
  value,
  disabled,
  onChange,
}: {
  label: string;
  hint: string;
  value: boolean;
  disabled?: boolean;
  onChange: (value: boolean) => void;
}) {
  const t = useTheme();
  return (
    <View style={[styles.row, {opacity: disabled ? 0.4 : 1}]}>
      <View style={styles.rowText}>
        <Text style={[styles.rowLabel, {color: t.text}]}>{label}</Text>
        <Text style={[styles.rowHint, {color: t.textDim}]}>{hint}</Text>
      </View>
      <Switch value={value} onValueChange={onChange} disabled={disabled} />
    </View>
  );
}

export function ModelRow({
  model,
  selected,
  onPress,
}: {
  model: ModelInfo;
  selected: boolean;
  onPress: () => void;
}) {
  const t = useTheme();
  return (
    <Pressable
      onPress={model.installed ? onPress : undefined}
      style={[styles.row, {opacity: model.installed ? 1 : 0.45}]}>
      <View style={styles.rowText}>
        <Text style={[styles.rowLabel, {color: t.text}]}>{model.name}</Text>
        <Text style={[styles.rowHint, {color: t.textDim}]}>
          {model.installed ? model.note : 'Not pushed to this device'}
        </Text>
      </View>
      {selected && <Text style={[styles.check, {color: t.accent}]}>✓</Text>}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  scrim: {flex: 1, backgroundColor: '#00000066'},
  sheet: {
    paddingHorizontal: space.lg,
    paddingBottom: space.xxl,
    paddingTop: space.sm,
    borderTopLeftRadius: radius.lg,
    borderTopRightRadius: radius.lg,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  grabber: {
    width: 36,
    height: 4,
    borderRadius: radius.pill,
    alignSelf: 'center',
    marginBottom: space.md,
  },
  title: {fontSize: 13, fontWeight: '600', letterSpacing: 0.6, textTransform: 'uppercase', marginBottom: space.sm},
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: space.md,
    gap: space.md,
  },
  rowText: {flex: 1},
  rowLabel: {fontSize: 15.5},
  rowHint: {fontSize: 12.5, marginTop: 2},
  check: {fontSize: 17, fontWeight: '700'},
});
