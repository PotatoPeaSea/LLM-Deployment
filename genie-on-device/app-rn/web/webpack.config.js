/**
 * Bundles the shared React Native UI for the browser.
 *
 * Metro is not used for the web target. Metro's web support would work, but the
 * Android build already depends on this project's Metro config and Babel setup,
 * and a second, differently-configured Metro is a good way to break the build
 * that matters. webpack is entirely separate: nothing here is on the Android
 * path, so a mistake in this file cannot produce a bad APK.
 *
 * Three things make an unmodified RN app compile for the web:
 *
 *  1. `react-native` resolves to `react-native-web`.
 *  2. `.web.ts`/`.web.tsx` are tried before `.ts`/`.tsx`, which is what swaps
 *     `src/genie.ts` for `src/genie.web.ts` and `src/confirm.ts` for
 *     `src/confirm.web.ts` without either screen importing anything different.
 *  3. Dependencies ship untranspiled ES6+ and JSX, so node_modules cannot be
 *     blanket-excluded from Babel the way a web project normally would.
 */
const path = require('path');

const appDirectory = path.resolve(__dirname, '..');

// RN packages publish source, not compiled output, so these must go through
// Babel. Anything else in node_modules is already ES5-safe and is skipped.
const compileNodeModules = [
  'react-native',
  'react-native-web',
  '@react-native',
  '@react-native-async-storage/async-storage',
].map(name => path.resolve(appDirectory, 'node_modules', name));

module.exports = (_env, argv = {}) => {
  const isProduction = argv.mode === 'production';

  return {
    mode: isProduction ? 'production' : 'development',
    entry: path.resolve(appDirectory, 'web/index.js'),
    output: {
      path: path.resolve(appDirectory, 'web/dist'),
      filename: 'bundle.js',
      publicPath: '/',
      clean: true,
    },
    // Inline source maps would double the bundle the board has to serve; a
    // separate file is fetched only when devtools are actually open.
    devtool: isProduction ? 'source-map' : 'eval-source-map',
    resolve: {
      alias: {
        'react-native$': 'react-native-web',
      },
      // Order matters: .web.* must come first. See (2) above.
      extensions: [
        '.web.tsx', '.web.ts', '.web.jsx', '.web.js',
        '.tsx', '.ts', '.jsx', '.js',
      ],
    },
    module: {
      rules: [
        {
          test: /\.[jt]sx?$/,
          include: [
            path.resolve(appDirectory, 'App.tsx'),
            path.resolve(appDirectory, 'src'),
            path.resolve(appDirectory, 'web'),
            ...compileNodeModules,
          ],
          use: {
            loader: 'babel-loader',
            options: {
              // The app's own babel.config.js targets React Native's preset,
              // which assumes Metro. Configure the web build in place instead
              // of editing a file the Android build also reads.
              babelrc: false,
              configFile: false,
              presets: [
                ['@babel/preset-env', {targets: {browsers: ['last 2 versions']}}],
                ['@babel/preset-react', {runtime: 'automatic'}],
                '@babel/preset-typescript',
              ],
              plugins: ['@babel/plugin-transform-runtime'],
              cacheDirectory: true,
            },
          },
        },
        {
          test: /\.(png|jpe?g|gif|svg|ttf|woff2?)$/,
          type: 'asset/resource',
        },
      ],
    },
    plugins: [
      new (require('html-webpack-plugin'))({
        template: path.resolve(appDirectory, 'web/index.html'),
      }),
      new (require('webpack').DefinePlugin)({
        // react-native-web branches on both; without them the bundle throws on
        // first render with "__DEV__ is not defined".
        __DEV__: JSON.stringify(!isProduction),
        'process.env.NODE_ENV': JSON.stringify(isProduction ? 'production' : 'development'),
      }),
    ],
    devServer: {
      port: 3000,
      historyApiFallback: true,
      // `npm run start:web` runs the UI on the workstation against the board's
      // API through the adb tunnel, so the whole app is testable with hot
      // reload without redeploying the bundle on every edit.
      proxy: [{context: ['/api'], target: 'http://127.0.0.1:8080'}],
    },
  };
};
