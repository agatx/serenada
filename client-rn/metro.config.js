const path = require('path');
const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

/**
 * Metro configuration
 * https://facebook.github.io/metro/docs/configuration
 *
 * @type {import('metro-config').MetroConfig}
 */
const config = {
  resolver: {
    extraNodeModules: {
      nullthrows: path.resolve(__dirname, 'node_modules/nullthrows'),
    },
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
