/**
 * "Are you sure?", in the browser. See `confirm.ts` for why this pair exists.
 *
 * The platform dialog is the right control here rather than a styled in-app
 * modal: this is a destructive confirmation, it is used in exactly one place,
 * and the app already has a `Sheet` component that is not built for it.
 */
export function confirmDestructive(
  title: string,
  message: string,
  _confirmLabel: string,
  onConfirm: () => void,
): void {
  // eslint-disable-next-line no-alert
  if (window.confirm(message ? `${title}\n\n${message}` : title)) {
    onConfirm();
  }
}
