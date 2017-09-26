import React, {Component} from 'react';

export class Dashboard extends Component {
  render() {
    return (
      <section id="yetibot">

        <div className="columns">

          <aside className="menu column is-2">
            <a className="menu-label navbar-item"
              href="/" title="Yetibot">
              <img src="https://github.com/yetibot/yetibot.core/blob/web/resources/public/yetibot_logotype.png?raw=true" />
            </a>

            <ul className="menu-list">
              <li><a>Dashboard</a></li>
              <li><a>Activity</a></li>
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
