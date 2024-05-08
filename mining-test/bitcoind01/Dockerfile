FROM kylemanna/bitcoind:latest
COPY start-bitcoind.sh /usr/local/bin/start-bitcoind.sh
RUN chmod +x /usr/local/bin/start-bitcoind.sh
ENTRYPOINT ["/usr/local/bin/start-bitcoind.sh"]
CMD ["31591", "localhost:31592", "32591", "0.0.0.0/0", "admin", "admin"]
