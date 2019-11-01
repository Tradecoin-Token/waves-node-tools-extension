# Waves Node Tools Extension

This node extension allows miner to automate payouts for its lessors and to receive notifications about mining progress.

## How to install

### On Debian-based Linux
If your node is installed from `.deb` package:
1. download `.deb` package of the extension and install it
```
wget https://github.com/msmolyakov/Waves/releases/download/v1.1.5-0.4/node-tools_1.1.5-0.4.deb && sudo dpkg -i node-tools_1.1.5-0.4.deb
```
2. add to `/etc/waves/local.conf`
```
waves.extensions = [
  "im.mak.notifier.NodeToolsExtension"
]
node-tools {
  webhook {
    url = "https://example.com/webhook/1234567890" # SPECIFY YOUR ENDPOINT
    body = """%s"""
  }
}
```
3. restart the node:
```
sudo systemctl restart waves
```

If the node starts up successfully, you will receive a log message and a notification about it.

## Configuration

### Leasing payouts

Payout is disabled by default. To enable, add to `local.conf` file:

```
node-tools {
  payout {
    enable = yes
    from-height = 123456789 # starting at what height pay lessors
    interval = 10000 # how often to pay
    delay = 2000 # delay after the interval until payout
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

You can read the full list of properties in the [src/main/resources/reference.conf](reference.conf).

#### Enabling notifications

The extension notifies you about reward of each block it has generated.

Other types of notifications can be enabled in conf file:
```
node-tools {
  notifications {
    start-stop = yes
    waves-received = yes
    leasing = yes
    payouts = yes
  }
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

##### payouts
If interval was finished or payouts was executed.
