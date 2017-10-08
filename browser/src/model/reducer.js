import {createStore} from 'redux';
import {Provider, connect} from 'react-redux';

const initialState = {
  foo: "bar",
  adapters: {},
};

function reducer(state = initialState, action) {
  switch (action.type) {
    case 'NAV':
      return Object.assign({}, state, {
        path: action.path,
      });
    default:
      return state;
  }
}

export const store = createStore(reducer);
