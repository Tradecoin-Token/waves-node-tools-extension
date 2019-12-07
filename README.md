# Waves Node Tools Extension

This node extension allows miner to automate payouts for its lessors and to receive notifications about mining progress.

## Prerequisites

The extension assumes that you're using the v1.1.5 node (or which is specified in the extension version).

## How to install

### On Debian-based Linux
If your node is installed from `.deb` package:
1. download `.deb` package of the extension and install it with the following commands:
```bash
wget https://github.com/msmolyakov/waves-node-tools-extension/releases/download/v1.1.5-0.5.1/node-tools_1.1.5-0.5.1.deb
sudo dpkg -i node-tools_1.1.5-0.5.1.deb
```
2. add to `/etc/waves/local.conf`
```hocon
waves.extensions += "im.mak.nodetools.NodeToolsExtension"
node-tools {
  webhook {
    url = "SPECIFY YOUR ENDPOINT" # example: "https://example.com/webhook/1234567890"
    method = "POST"
    headers = [] # example: [ "Content-Type: application/json; charset=utf-8", "Authorization: Basic dXNlcjpwYXNzd29yZA==" ]
    body = "%s" # example for Integram: """{"text":"%s"}"""
  }
}
```
3. restart the node:
```bash
sudo systemctl restart waves
```

If the node starts up successfully, you will receive a log message and a notification about it.

## Configuration

### Leasing payouts

Payout is disabled by default. To enable, add to `local.conf` file:

```hocon
node-tools {
  payout {
    enable = yes
    from-height = 123456789 # starting at what height to pay lessors
    interval = 10000 # how often (in blocks) to pay
    delay = 2000 # delay in blocks after the interval until payout
    percent = 50 # which amount of mined Waves to payout for lessors
  }
}
```

#### How it works

The extension writes information about all mined blocks and payouts into local database, stored in `/var/lib/waves` folder.

For each interval it calculates contribution of each lessor to the generating balance and register to the database future payments for each lessor proportionally.

Before payment, a delay is used in case the node is forked or some other unforeseen event occurs.

If any payments were not made at the appointed time, then this extension will try to execute them even if the node restarts or rolls back no further than the interval.

*Important:* do not lose the database file, otherwise you will lose information about all payments made and planned!

### Notifications

The extension can notify you about some events related to block generation.

#### Webhook messages

By default the extension writes notifications to the node log file. In addition, you can specify any http endpoint for notifications.

For example, you can use Telegram bot https://t.me/bullhorn_bot from https://integram.org/ team (add this bot and read its welcome message).

You can read the full list of properties in the [reference.conf](node-tools/src/main/resources/reference.conf).

#### Enabling notifications

The extension notifies you about reward of each block it has generated.

Other types of notifications can be enabled in conf file:
```hocon
node-tools {
  notifications {
    start-stop = yes
    waves-received = yes
    leasing = yes
    mined-block = yes
  }
  block-url = "https://wavesexplorer.com/blocks/%s"
}
```

##### start-stop
When the node starts, it sends message why the node will not generate blocks:
- if mining disabled in config
- a generating balance is less than 1000 Waves
- the miner account has a smart contract

Also it sends notification if the node was stopped.

##### waves-received
If the Node address receives some Waves.

##### leasing
If leased volume is changed.

##### mined-block
If the node generated a block.

##### Customize the url to info about generated block

When the extension sends message about mined block, it provides url to this block. By default it's url to the Waves Explorer for Mainnet.

You can change this url to yor own in the `block-url` field. For example:
- Explorer for Testnet `"https://wavesexplorer.com/testnet/blocks/%s"`
- REST API for Mainnet `"https://nodes.wavesnodes.com/blocks/headers/at/%s"`
- REST API of your local node (if enabled) `"http://127.0.0.1:6869/blocks/headers/at/%s"`

### Database

The extension stores information about all payouts in the local database by default. Default settings:
```hocon
node-tools {
  db {
    path = ${user.home}/node-tools/data
    path = ${?WAVES_MNEXT_DB}

    ctx {
      dataSourceClassName = org.h2.jdbcx.JdbcDataSource
      dataSource.url = "jdbc:h2:file:"${node-tools.db.path}";INIT=RUNSCRIPT FROM 'classpath:mnext-init.sql'"
      dataSource.user = sa
    }
  }
}
```

By default, you can find this file in the `/var/lib/waves/node-tools` directory.

**DO NOT DELETE THIS DATABASE IF YOU DO NOT WANT TO LOSE INFORMATION ABOUT ALL YOUR PAYMENTS!**

## How to update

```bash
wget https://github.com/msmolyakov/waves-node-tools-extension/releases/download/v1.1.5-0.5.1/node-tools_1.1.5-0.5.1.deb
sudo apt remove node-tools
sudo dpkg -i node-tools_1.1.5-0.5.1.deb
```

These commands remove only binaries of this extension. Database with payouts information will be kept.
