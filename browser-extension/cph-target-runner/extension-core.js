(function (root) {
  const DEFAULT_PORTS = [10043, 10044, 10045, 10046, 10047, 1327];
  const FETCH_TIMEOUT_MS = 1500;

  async function getPorts() {
    const stored = await chrome.storage.local.get(["customPorts", "lastPort"]);
    const base = Array.isArray(stored.customPorts) && stored.customPorts.length > 0
      ? stored.customPorts.slice()
      : DEFAULT_PORTS.slice();
    if (stored.lastPort) {
      const i = base.indexOf(stored.lastPort);
      if (i > 0) {
        base.splice(i, 1);
        base.unshift(stored.lastPort);
      } else if (i < 0) {
        base.unshift(stored.lastPort);
      }
    }
    return base;
  }

  async function rememberTab(tab, port) {
    await chrome.storage.local.set({
      lastReportedUrl: tab.url,
      lastReportedAt: Date.now(),
      lastPort: port,
    });
  }

  async function readLastReport() {
    return chrome.storage.local.get(["lastReportedUrl", "lastReportedAt", "lastPort"]);
  }

  function tabPayload(tab) {
    return { url: tab.url, title: tab.title || "", ts: Date.now() };
  }

  async function postFirstReachable(path, body, timeoutMs = FETCH_TIMEOUT_MS) {
    const ports = await getPorts();
    const failures = [];
    for (const port of ports) {
      try {
        const res = await CphSubmitCore.postJsonWithTimeout(
          `http://127.0.0.1:${port}${path}`,
          body,
          timeoutMs,
        );
        if (res.ok) return { port, res };
        failures.push(`${port}: HTTP ${res.status}`);
      } catch (err) {
        failures.push(`${port}: ${err && err.name === "AbortError" ? "timeout" : "blocked/offline"}`);
      }
    }
    throw new Error(failures.join("; "));
  }

  async function reportActiveTab(tab) {
    const body = tabPayload(tab);
    const { port, res } = await postFirstReachable("/cph/active-tab", body);
    await rememberTab(tab, port);
    return { port, res };
  }

  root.CphTargetRunnerExtension = {
    FETCH_TIMEOUT_MS,
    getPorts,
    rememberTab,
    readLastReport,
    tabPayload,
    postFirstReachable,
    reportActiveTab,
  };
})(globalThis);
