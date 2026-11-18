#!/usr/bin/env bash
# Experiment B5: are the five RSKIP704 refund output types standard for Bitcoin Core, and what is each one's
# dust threshold at default settings?
#
# Usage: b5-core-standardness.sh <container-name> <file with lines "TYPE scriptPubKeyHex">
# The scripts are the ones rskj builds (PeginRefundScriptType.buildOutputScript), printed by
# V3StrictRefundSizeExperimentTest.b5_printRefundOutputScripts as "V3STRICT-B5 TYPE hex".
# Needs a running regtest bitcoind container with a funded default wallet.
set -euo pipefail
CONTAINER="$1"
SCRIPTS="$2"
cli() { podman exec --user bitcoin "$CONTAINER" bitcoin-cli -regtest "$@"; }

echo "Core: $(cli getnetworkinfo | grep '"subversion"')"
cli getmempoolinfo | grep -E '"minrelaytxfee"|"mempoolminfee"|"incrementalrelayfee"'

declare -a TYPES ADDRS HEXES
while read -r type hex; do
  [ -z "$type" ] && continue
  addr=$(cli decodescript "$hex" | sed -n 's/.*"address": "\(.*\)".*/\1/p' | head -1)
  TYPES+=("$type"); HEXES+=("$hex"); ADDRS+=("$addr")
  echo "script $type $hex -> $(cli decodescript "$hex" | sed -n 's/.*"type": "\(.*\)".*/\1/p' | head -1) $addr"
done < "$SCRIPTS"

# one transaction paying 10,000 sat to each of the five scripts
outputs="["
for i in "${!TYPES[@]}"; do outputs+="{\"${ADDRS[$i]}\":0.0001},"; done
outputs="${outputs%,}]"
raw=$(cli createrawtransaction '[]' "$outputs")
funded=$(cli fundrawtransaction "$raw" | sed -n 's/.*"hex": "\(.*\)".*/\1/p')
signed=$(cli signrawtransactionwithwallet "$funded" | sed -n 's/.*"hex": "\(.*\)".*/\1/p')
decoded=$(cli decoderawtransaction "$signed")
for i in "${!TYPES[@]}"; do
  if echo "$decoded" | grep -q "\"hex\": \"${HEXES[$i]}\""; then
    echo "output ${TYPES[$i]}: scriptPubKey in the transaction is byte-identical to the rskj script"
  else
    echo "output ${TYPES[$i]}: SCRIPT MISMATCH"; exit 1
  fi
done
echo "testmempoolaccept (all five types, 10000 sat each): $(cli testmempoolaccept "[\"$signed\"]" | tr -d '\n ' )"

# dust threshold per type: the smallest value testmempoolaccept does not reject as dust.
# The transaction is assembled by hand from one wallet UTXO (the wallet's own funding refuses dust outputs
# before the mempool policy is ever asked) and is never broadcast.
utxo=$(cli listunspent 1 9999999 '[]' true '{"maximumCount":1}')
UTXO_TXID=$(echo "$utxo" | sed -n 's/.*"txid": "\(.*\)".*/\1/p' | head -1)
UTXO_VOUT=$(echo "$utxo" | sed -n 's/.*"vout": \([0-9]*\).*/\1/p' | head -1)
UTXO_SATS=$(echo "$utxo" | sed -n 's/.*"amount": \([0-9.]*\).*/\1/p' | head -1 | tr -d '.' | sed 's/^0*//')
CHANGE_ADDR=$(cli getrawchangeaddress)
sats_to_btc() { printf '%d.%08d' $(( $1 / 100000000 )) $(( $1 % 100000000 )); }
accepts() { # address, sats
  local raw signed result change
  change=$(( UTXO_SATS - $2 - 10000 ))
  raw=$(cli createrawtransaction "[{\"txid\":\"$UTXO_TXID\",\"vout\":$UTXO_VOUT}]" "[{\"$1\":$(sats_to_btc "$2")},{\"$CHANGE_ADDR\":$(sats_to_btc "$change")}]")
  signed=$(cli signrawtransactionwithwallet "$raw" | sed -n 's/.*"hex": "\(.*\)".*/\1/p')
  result=$(cli testmempoolaccept "[\"$signed\"]" | tr -d '\n ')
  LAST="$result"
  echo "$result" | grep -q '"allowed":true'
}
for i in "${!TYPES[@]}"; do
  low=1; high=2000   # low is rejected, high accepted
  accepts "${ADDRS[$i]}" $high || { echo "unexpected: ${TYPES[$i]} rejected at $high: $LAST"; exit 1; }
  accepts "${ADDRS[$i]}" $low && { echo "unexpected: ${TYPES[$i]} accepted at $low"; exit 1; }
  reason=$(echo "$LAST" | sed -n 's/.*"reject-reason":"\([^"]*\)".*/\1/p')
  while [ $((high - low)) -gt 1 ]; do
    mid=$(( (low + high) / 2 ))
    if accepts "${ADDRS[$i]}" $mid; then high=$mid; else low=$mid; fi
  done
  echo "dust threshold ${TYPES[$i]}: smallest accepted value $high sat (rejected below with reason \"$reason\")"
done
