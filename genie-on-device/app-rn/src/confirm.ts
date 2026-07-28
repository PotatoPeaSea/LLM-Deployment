/**
 * "Are you sure?", on Android.
 *
 * Exists because `Alert` is the one React Native primitive this app uses that
 * react-native-web does not implement — it exports the module, but `Alert.alert`
 * is a no-op there, which would silently turn "delete chat" into a control that
 * does nothing. Routing the single call site through this pair
 * (`confirm.ts` / `confirm.web.ts`) keeps the screens identical on both targets.
 */
import {Alert} from 'react-native';

/** Ask for confirmation; `onConfirm` runs only if the user agrees. */
export function confirmDestructive(
  title: string,
  message: string,
  confirmLabel: string,
  onConfirm: () => void,
): void {
  Alert.alert(title, message, [
    {text: 'Cancel', style: 'cancel'},
    {text: confirmLabel, style: 'destructive', onPress: onConfirm},
  ]);
}
