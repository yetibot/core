import React, {Component} from 'react';

const Item = stuff => {
  return (
    <pre>
      Item!
      {JSON.stringify(stuff)}
    </pre>
  );
};

class Foo extends Component {
  render() {
    return (
      <pre>{JSON.stringify(this.props)}</pre>
    )
  }
}

export class Dashboard extends Component {
  render() {
    return (
      <section id="yetibot">
        <pre>{JSON.stringify(this.props)}</pre>

        <div>
          Item:
          <Item wat="wat" />
        </div>

        <div>
          Foo:
          <Foo />
        </div>

        <div className="columns">
          <aside className="menu column is-2">
            <a className="menu-label navbar-item" href="/" title="Yetibot">
              <img src="https://github.com/yetibot/yetibot.core/blob/web/resources/public/yetibot_logotype.png?raw=true" />
            </a>

            <ul className="menu-list">
              <li>
                <a>Dashboard</a>
              </li>
              <li>
                <a>Activity</a>
              </li>
              <li>
                <a>{this.props.name}</a>
              </li>
              <li>
                <a>{this.props.age}</a>
              </li>
              <li>
                <a>{this.props.location}</a>
              </li>
            </ul>
          </aside>

          <div className="dashboard column is-10 hero-body">
            <h1 className="title">Active adapters</h1>
          </div>
        </div>
      </section>
    );
  }
}
export default Dashboard;
