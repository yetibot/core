const path = require('path');

module.exports = {
  entry: './browser/src/index.js',
  output: {
    // TODO move this to resources/public served by ring
    path: path.resolve(__dirname, 'browser', 'output'),
    filename: 'bundle.js',
  },
  resolve: {
    extensions: ['.js', '.jsx'],
    modules: [
      path.resolve('./browser/src'),
      path.resolve('./node_modules')
    ]
  },
  module: {
    rules: [
      {
        test: /\.jsx?$/,
        loader: 'babel-loader',
        options: { presets: ['react', 'es2015'] },
        exclude: /node_modules/,
      },
      {
        test: /\.css$/,
        use: ['style-loader', 'css-loader'],
      },
      {
        test: /\.scss/,
        use: ['style-loader', 'css-loader', 'sass-loader']
      }
    ],
  },

  // devServer: {
  //   contentBase: './browser/output',
  // },

  devServer: {
    contentBase: './browser/src',
    publicPath: '/output'
  }

};

// path: path.resolve(__dirname, 'output'),
