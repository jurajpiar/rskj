#!/usr/bin/env bash
# Experiment B2, Bitcoin Core side.
#   b2-core-check.sh deposit <container> <addresses file> <deposits file>
#       addresses file: lines "caseId activeFederationAddress retiringFederationAddress"
#       (V3StrictTwoFederationsSigningExperimentTest prints them as "V3STRICT-B2-ADDR ...").
#       Pays 0.01 BTC to each of the two addresses in one transaction per case, mines it, and writes
#       "caseId rawTxHex" lines to the deposits file (rskj-core/build/v3strict/b2-deposits.txt).
#   b2-core-check.sh verify <container> <releases file>
#       releases file: lines "caseId fully-signed|incomplete rawTxHex", written by the test.
#       Runs testmempoolaccept on each.
set -euo pipefail
MODE="$1"; CONTAINER="$2"
cli() { podman exec --user bitcoin "$CONTAINER" bitcoin-cli -regtest "$@"; }
if [ "$MODE" = deposit ]; then
  : > "$4"
  while read -r id active retiring; do
    [ -z "$id" ] && continue
    txid=$(cli sendmany "" "{\"$active\":0.01,\"$retiring\":0.01}")
    echo "$id $(cli getrawtransaction "$txid")" >> "$4"
    echo "deposit $id $txid"
  done < "$3"
  cli generatetoaddress 1 "$(cli getnewaddress)" >/dev/null
else
  while read -r id state hex; do
    [ -z "$id" ] && continue
    echo "release $id ($state by the Bridge): $(cli testmempoolaccept "[\"$hex\"]" | tr -d '\n ')"
  done < "$3"
fi
