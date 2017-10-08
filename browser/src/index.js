import React from 'react';
import ReactDOM from 'react-dom';
import Dashboard from 'components/dashboard';
import {store} from 'model/reducer';
import {Provider, connect} from 'react-redux';

import './styles/app.scss';

const mapStateToProps = state => {
  return state
  // return {
  //   filterBy: state.filterBy,
  // };
};

const mapDispatchToProps = (dispatch) => {
  return {
  }
}

const DashboardContainer =
  connect(mapStateToProps, mapDispatchToProps)(Dashboard);

ReactDOM.render(
  <Provider store={store}>
    <DashboardContainer />
  </Provider>,
  document.getElementById('app'),
);
