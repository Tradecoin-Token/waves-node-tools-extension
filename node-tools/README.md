# Miner notifier extension for Waves Node
//TODO waves-node-tools-extension
//TODO update link in awesome-waves

## What can do

### Leasing payouts
- interval, delay, percent, asset

### Notifications
- when the node starts, it sends message why the node will not generate blocks:
  - if mining disabled in config
  - a generating balance is less than 1000 Waves
  - the miner account has a smart contract
- notifies if the node mined block and its reward
- notifies about incoming Waves or MRT payments
- notifies about changes of leased volume.

## How to install:
1. download `miner-notifier-0.3.4.jar` to `/usr/share/waves/lib/`:
```
wget https://github.com/msmolyakov/waves-node-notifier-extension/releases/download/v0.3.4/miner-notifier-0.3.4.jar -P /usr/share/waves/lib/
```
2. download official `scalaj-http_2.12-2.4.2.jar` from Maven Central to `/usr/share/waves/lib/`:
```
wget https://repo1.maven.org/maven2/org/scalaj/scalaj-http_2.12/2.4.2/scalaj-http_2.12-2.4.2.jar -P /usr/share/waves/lib/
```
3. add to `/etc/waves/waves.conf` (or `local.conf`):
```
waves.extensions = [
  "im.mak.notifier.MinerNotifierExtension"
]
miner-notifier.webhook {
  # url = "https://example.com/webhook/1234567890" # SPECIFY YOUR ENDPOINT
  # body = """Mainnet: %s"""
}
```
4. restart the node

If node starts successfully, you will receive message about this.

## How to configure

## How payouts work

## Notifications

By default the extension writes notifications to the node log file. In addition, you can specify any endpoint of notifications.

For example, you can use Telegram bot https://t.me/bullhorn_bot from https://integram.org/ team (add this bot and read its welcome message).

You can read the full list of properties in the [src/main/resources/application.conf](application.conf).
