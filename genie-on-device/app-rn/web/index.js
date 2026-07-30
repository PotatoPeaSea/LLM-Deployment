/**
 * Web entry point.
 *
 * The Android entry is `index.js` at the project root, which hands `App` to
 * `AppRegistry` and lets the native host mount it. This does the same thing and
 * then explicitly runs the application into a DOM node, which is the one step
 * the native host would otherwise do for us.
 *
 * `App` itself is imported unchanged — the whole point of the react-native-web
 * approach is that the screens do not know which target they are on.
 */
import {AppRegistry} from 'react-native';
import App from '../App';
// Default import, not `{name}`: app.json is JSON, and webpack only guarantees
// the default export for it.
import appJson from '../app.json';

AppRegistry.registerComponent(appJson.name, () => App);

AppRegistry.runApplication(appJson.name, {
  rootTag: document.getElementById('root'),
});
