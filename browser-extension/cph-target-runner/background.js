importScripts("submit-core.js", "extension-core.js");

const DEBOUNCE_MS = 200;
const POLL_TIMEOUT_MS = 22000;
const POLL_IDLE_DELAY_MS = 600;
const HEARTBEAT_ALARM = "cph-heartbeat";
const HEARTBEAT_PERIOD_MIN = 0.5;
const POLLING_KEYS = new Set();

let pending = null;

async function postWithTimeout(url, body, timeoutMs) {
  return CphSubmitCore.postJsonWithTimeout(url, body, timeoutMs);
}

async function cfFetch(url, init = {}) {
  return fetch(url, {
    credentials: "include",
    cache: "no-store",
    ...init,
    headers: init.headers || {},
  });
}

async function activeTabOf(windowId) {
  const tabs = await chrome.tabs.query({ active: true, windowId });
  return tabs[0];
}

async function focusedCfTab() {
  const wins = await chrome.windows.getAll({ populate: false });
  const focused = wins.find((w) => w.focused) || wins[0];
  if (!focused) return null;
  const tab = await activeTabOf(focused.id);
  return tab && CphSubmitCore.isProblemUrl(tab.url) ? tab : null;
}

async function postActiveTab(tab) {
  if (!tab || !CphSubmitCore.isProblemUrl(tab.url)) return;
  const body = CphTargetRunnerExtension.tabPayload(tab);
  const ports = await CphTargetRunnerExtension.getPorts();
  for (const port of ports) {
    try {
      const res = await postWithTimeout(`http://127.0.0.1:${port}/cph/active-tab`, body, CphTargetRunnerExtension.FETCH_TIMEOUT_MS);
      if (res.ok) {
        await CphTargetRunnerExtension.rememberTab(tab, port);
        return;
      }
    } catch (_) {
      // try next port
    }
  }
}

function schedulePost(tab) {
  if (pending) clearTimeout(pending);
  pending = setTimeout(() => {
    pending = null;
    postActiveTab(tab);
    scheduleSubmitPoll(tab);
  }, DEBOUNCE_MS);
}

async function scheduleSubmitPoll(tab) {
  if (!tab || !CphSubmitCore.isProblemUrl(tab.url)) return;
  const key = `${tab.id}:${tab.url}`;
  if (POLLING_KEYS.has(key)) return;
  POLLING_KEYS.add(key);
  try {
    await pollLoop(tab, key);
  } finally {
    POLLING_KEYS.delete(key);
  }
}

async function pollLoop(tab, key) {
  while (true) {
    if (!(await tabStillActive(tab))) return;
    const body = CphTargetRunnerExtension.tabPayload(tab);
    const ports = await CphTargetRunnerExtension.getPorts();
    let contacted = false;
    for (const port of ports) {
      try {
        const res = await postWithTimeout(`http://127.0.0.1:${port}/cph/submit/poll`, body, POLL_TIMEOUT_MS);
        if (res.status === 200) {
          await CphTargetRunnerExtension.rememberTab(tab, port);
          const job = await res.json();
          await handleSubmitJob(tab, port, job);
          return;
        }
        if (res.status === 204 || res.ok) {
          await CphTargetRunnerExtension.rememberTab(tab, port);
          contacted = true;
          break;
        }
      } catch (_) {
        // try next port
      }
    }
    if (!contacted && key) return;
    await sleep(POLL_IDLE_DELAY_MS);
  }
}

async function tabStillActive(original) {
  try {
    const tab = await chrome.tabs.get(original.id);
    return tab.active && tab.url === original.url;
  } catch (_) {
    return false;
  }
}

async function handleSubmitJob(tab, port, job) {
  await sendUpdate(port, job, "SUBMITTING", `→ ${job.displayId}  Opening Submit Code tab…`);
  try {
    const queued = await submitInCodeforcesPage(tab, job, port);
    await sendUpdate(port, job, "QUEUED", `#${queued.submissionId}  In queue`, {
      submissionId: queued.submissionId,
      pageUrl: queued.pageUrl,
    });
    const result = await pollVerdict(queued.handle, queued.submissionId, queued.pageUrl, port, job);
    await sendUpdate(port, job, result.phase, `#${result.submissionId}  ${result.display}`, {
      submissionId: result.submissionId,
      pageUrl: result.pageUrl,
    });
  } catch (err) {
    await sendUpdate(port, job, "ERROR", `Failed: ${err.message || err}`, {
      errorDetail: err.message || String(err),
    });
  } finally {
    scheduleSubmitPoll(tab);
  }
}

async function submitInCodeforcesPage(problemTab, job, port) {
  const submitTab = await openSubmitTab(problemTab, job.submitPageUrl);
  const tabId = submitTab.id;
  await sendUpdate(port, job, "SUBMITTING", `→ ${job.displayId}  Filling Submit Code form…`);
  await chrome.scripting.executeScript({
    target: { tabId },
    files: ["submit-core.js", "page-submit.js"],
    world: "MAIN",
  });
  const results = await chrome.scripting.executeScript({
    target: { tabId },
    func: (submitJob) => new Promise((resolve, reject) => {
      const requestId = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
      const timer = setTimeout(() => {
        window.removeEventListener("message", onMessage);
        reject(new Error("Codeforces page submit timed out"));
      }, 45000);
      function onMessage(event) {
        if (event.source !== window) return;
        const msg = event.data;
        if (msg && msg.type === "CPH_SUBMIT_PROGRESS" && msg.debug) {
          console.info(`[CPH Target Runner] ${msg.debug}`);
          fetch(`http://127.0.0.1:${submitJob.__cphPort}/cph/submit/update`, {
            method: "POST",
            headers: { "Content-Type": "text/plain;charset=UTF-8" },
            body: JSON.stringify({
              jobId: submitJob.jobId,
              phase: "RUNNING",
              text: `→ ${submitJob.displayId}  ${msg.debug}`,
            }),
          }).catch(() => {});
          return;
        }
        if (!msg || msg.type !== "CPH_SUBMIT_RESULT" || msg.requestId !== requestId) return;
        clearTimeout(timer);
        window.removeEventListener("message", onMessage);
        if (msg.ok) {
          resolve(msg.result);
        } else {
          reject(new Error(msg.error || "Codeforces page submit failed"));
        }
      }
      window.addEventListener("message", onMessage);
      window.postMessage({ type: "CPH_SUBMIT_JOB", requestId, job: submitJob }, "*");
    }),
    args: [{ ...job, __cphPort: port }],
  });
  const result = results && results[0] && results[0].result;
  if (!result || !result.handle) {
    throw new Error("Codeforces Submit Code page did not start submission");
  }
  await sendUpdate(port, job, "SUBMITTING", `→ ${job.displayId}  Submit request sent; waiting for Codeforces id…`);
  const submission = await waitForNewSubmission(
    result.handle,
    job,
    Number(result.beforeMaxId || 0),
    Number(result.startedAtSeconds || 0),
  );
  if (!submission) {
    const pageError = await readSubmitPageError(tabId);
    throw new Error(pageError || "Codeforces Submit Code page did not create a submission");
  }
  return {
    handle: result.handle,
    submissionId: submission.id,
    pageUrl: CphSubmitCore.submissionPageUrl(submission),
  };
}

function openSubmitTab(problemTab, url) {
  return new Promise((resolve, reject) => {
    if (!problemTab || !problemTab.id || !problemTab.windowId) {
      reject(new Error("No Codeforces problem tab is available for submission"));
      return;
    }
    let settled = false;
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("Codeforces Submit Code page load timed out"));
    }, 20000);
    function cleanup() {
      clearTimeout(timer);
      chrome.tabs.onUpdated.removeListener(onUpdated);
    }
    function finish(tab) {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(tab);
    }
    function onUpdated(updatedTabId, changeInfo, tab) {
      if (submitTabId !== null && updatedTabId === submitTabId && changeInfo.status === "complete") finish(tab);
    }
    let submitTabId = null;
    chrome.tabs.onUpdated.addListener(onUpdated);
    const createProperties = {
      windowId: problemTab.windowId,
      openerTabId: problemTab.id,
      url,
      active: true,
    };
    if (Number.isInteger(problemTab.index)) createProperties.index = problemTab.index + 1;
    chrome.tabs.create(createProperties)
      .then((tab) => {
        submitTabId = tab.id;
        if (tab.status === "complete" && tab.url && samePage(tab.url, url)) finish(tab);
      })
      .catch((err) => {
        if (settled) return;
        settled = true;
        cleanup();
        reject(err);
      });
  });
}

function samePage(left, right) {
  try {
    const a = new URL(left);
    const b = new URL(right);
    return a.origin === b.origin && a.pathname === b.pathname;
  } catch (_) {
    return left === right;
  }
}

async function waitForNewSubmission(handle, job, beforeMaxId, startedAtSeconds) {
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    const arr = await fetchStatus(handle, 20);
    const submission = arr.find((item) =>
      Number(item.id || 0) > beforeMaxId &&
      (!startedAtSeconds || Number(item.creationTimeSeconds || 0) >= startedAtSeconds - 10) &&
      CphSubmitCore.matchesSubmission(job, item)
    );
    if (submission) return submission;
    await sleep(1000);
  }
  return null;
}

async function readSubmitPageError(tabId) {
  try {
    const results = await chrome.scripting.executeScript({
      target: { tabId },
      func: () => {
        const text = (node) => node ? node.textContent.replace(/\s+/g, " ").trim() : "";
        const error = Array.from(document.querySelectorAll(".error, .genericError, .alert, .notice"))
          .map(text)
          .find((value) => value && !/^Source code:?$/i.test(value));
        if (error) return error;
        if (/\/enter\?back=|handleOrEmail|password/i.test(document.documentElement.innerHTML)) {
          return "please log in to Codeforces in this browser";
        }
        return "";
      },
    });
    const error = results && results[0] && results[0].result;
    return error || "";
  } catch (_) {
    return "";
  }
}

async function pollVerdict(handle, submissionId, pageUrl, port, job) {
  const deadline = Date.now() + 120000;
  let last = null;
  while (Date.now() < deadline) {
    const submission = await fetchSubmissionById(handle, submissionId);
    if (submission) {
      const verdict = CphSubmitCore.verdictFromSubmission(submission);
      last = verdict;
      await sendUpdate(port, job, verdict.phase, `#${submissionId}  ${verdict.display}`, {
        submissionId,
        pageUrl,
      });
      if (verdict.terminal) {
        return {
          phase: verdict.phase,
          display: verdict.display,
          submissionId,
          pageUrl,
        };
      }
    }
    await sleep(1500);
  }
  if (last) {
    return {
      phase: last.phase,
      display: last.display,
      submissionId,
      pageUrl,
    };
  }
  throw new Error("verdict polling timed out");
}

async function fetchSubmissionById(handle, submissionId) {
  const arr = await fetchStatus(handle, 20);
  return arr.find((submission) => Number(submission.id) === Number(submissionId)) || null;
}

async function fetchStatus(handle, count) {
  const url = `https://codeforces.com/api/user.status?handle=${encodeURIComponent(handle)}&from=1&count=${count}`;
  const resp = await cfFetch(url);
  if (!resp.ok) return [];
  const root = await resp.json();
  if (!root || root.status !== "OK" || !Array.isArray(root.result)) return [];
  return root.result;
}

async function sendUpdate(port, job, phase, text, extra = {}) {
  const payload = {
    jobId: job.jobId,
    phase,
    text,
    ...extra,
  };
  try {
    await postWithTimeout(`http://127.0.0.1:${port}/cph/submit/update`, payload, CphTargetRunnerExtension.FETCH_TIMEOUT_MS);
  } catch (_) {
    // The IDE may have closed; there is nowhere better to report this from the extension.
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

chrome.tabs.onActivated.addListener(async ({ tabId }) => {
  try {
    const tab = await chrome.tabs.get(tabId);
    schedulePost(tab);
  } catch (_) {}
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (!tab.active) return;
  if (changeInfo.url || changeInfo.status === "complete") schedulePost(tab);
});

chrome.windows.onFocusChanged.addListener(async (windowId) => {
  if (windowId === chrome.windows.WINDOW_ID_NONE) return;
  try {
    const tab = await activeTabOf(windowId);
    if (tab) schedulePost(tab);
  } catch (_) {}
});

async function heartbeatTick() {
  try {
    const tab = await focusedCfTab();
    if (tab) {
      postActiveTab(tab);
      scheduleSubmitPoll(tab);
    }
  } catch (_) {}
}

chrome.alarms.create(HEARTBEAT_ALARM, {
  delayInMinutes: HEARTBEAT_PERIOD_MIN,
  periodInMinutes: HEARTBEAT_PERIOD_MIN,
});
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === HEARTBEAT_ALARM) heartbeatTick();
});

chrome.runtime.onStartup.addListener(() => { heartbeatTick(); });
chrome.runtime.onInstalled.addListener(() => { heartbeatTick(); });
heartbeatTick();

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg && msg.type === "submitNow") {
    (async () => {
      const tab = await focusedCfTab();
      if (!tab) {
        sendResponse({ ok: false, error: "current tab is not a CF problem page" });
        return;
      }
      const ports = await CphTargetRunnerExtension.getPorts();
      const body = CphTargetRunnerExtension.tabPayload(tab);
      for (const port of ports) {
        try {
          const res = await postWithTimeout(`http://127.0.0.1:${port}/cph/submit-now`, body, CphTargetRunnerExtension.FETCH_TIMEOUT_MS);
          if (res.ok) {
            await CphTargetRunnerExtension.rememberTab(tab, port);
            scheduleSubmitPoll(tab);
            sendResponse({ ok: true });
            return;
          }
        } catch (_) {}
      }
      sendResponse({ ok: false, error: "no IDE listening on configured ports" });
    })();
    return true;
  }
});
