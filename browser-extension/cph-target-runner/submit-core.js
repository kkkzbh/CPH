(function (root) {
  const CF_PROBLEM_RE = /^https?:\/\/(?:www\.)?codeforces\.com\/(?:contest|gym)\/[^/]+\/problem\/[^/?#]+/i;
  const CF_PROBLEMSET_RE = /^https?:\/\/(?:www\.)?codeforces\.com\/problemset\/problem\/[^/]+\/[^/?#]+/i;
  const CF_ACMSGURU_RE = /^https?:\/\/(?:www\.)?codeforces\.com\/problemsets\/acmsguru\/problem\/[^/]+\/[^/?#]+/i;

  function isProblemUrl(url) {
    if (!url) return false;
    return CF_PROBLEM_RE.test(url) || CF_PROBLEMSET_RE.test(url) || CF_ACMSGURU_RE.test(url);
  }

  async function postJsonWithTimeout(url, body, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "text/plain;charset=UTF-8" },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timer);
    }
  }

  function extractCsrf(html) {
    return match(html, /<meta[^>]+name=["']X-Csrf-Token["'][^>]+content=["']([0-9a-f]+)["']/i)
      || match(html, /<input[^>]+name=["']csrf_token["'][^>]+value=["']([0-9a-f]+)["']/i)
      || match(html, /data-csrf=["']([0-9a-f]+)["']/i);
  }

  function extractHandle(html) {
    return match(html, /<a href=["']\/profile\/([A-Za-z0-9_.-]+)["'][^>]*class=["'][^"']*lang-chooser-link[^"']*["']/i)
      || match(html, /<a href=["']\/profile\/([A-Za-z0-9_.-]+)["']/i);
  }

  function extractSubmitError(html) {
    const span = match(html, /<span\s+class=["']error[^"']*["'][^>]*>([\s\S]{1,600}?)<\/span>/i);
    if (span) {
      const text = stripTags(span).trim();
      if (text && !/class=["']submit["']/i.test(text)) return text;
    }
    const notice = match(html, /<div\s+class=["'][^"']*(?:alert|notice|error)[^"']*["'][^>]*>([\s\S]{1,900}?)<\/div>/i);
    if (notice) {
      const text = stripTags(notice).trim();
      if (text) return text;
    }
    if (/Source should not exceed/i.test(html)) return "source too large";
    if (/You have submitted exactly the same code/i.test(html)) return "identical to a previous submission";
    if (/Invalid handle or password|Logout|Enter\s*\|/i.test(html) && /\/enter\?back=|handleOrEmail|password/i.test(html)) {
      return "please log in to Codeforces in this browser";
    }
    return "";
  }

  function verdictFromSubmission(submission) {
    const raw = submission.verdict || "";
    const passed = Number(submission.passedTestCount || 0);
    const timeMs = Number(submission.timeConsumedMillis || 0);
    const memoryBytes = Number(submission.memoryConsumedBytes || 0);
    const terminal = raw !== "" && raw !== "TESTING";
    const accepted = raw === "OK";
    const nextTest = passed + 1;
    let display;
    if (!terminal) display = raw === "TESTING" ? `Running on test ${nextTest}` : "In queue";
    else if (accepted) display = `Accepted · ${timeMs} ms · ${Math.floor(memoryBytes / 1024)} KB`;
    else if (raw === "WRONG_ANSWER") display = `Wrong answer on test ${nextTest} · ${timeMs} ms`;
    else if (raw === "TIME_LIMIT_EXCEEDED") display = `Time limit exceeded on test ${nextTest}`;
    else if (raw === "MEMORY_LIMIT_EXCEEDED") display = `Memory limit exceeded on test ${nextTest}`;
    else if (raw === "RUNTIME_ERROR") display = `Runtime error on test ${nextTest}`;
    else if (raw === "IDLENESS_LIMIT_EXCEEDED") display = `Idleness limit exceeded on test ${nextTest}`;
    else if (raw === "COMPILATION_ERROR") display = "Compilation error";
    else if (raw === "PRESENTATION_ERROR") display = `Presentation error on test ${nextTest}`;
    else if (raw === "PARTIAL") display = `Partial · passed ${passed}`;
    else if (raw === "CHALLENGED") display = `Hacked · ${timeMs} ms`;
    else display = raw.toLowerCase().replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
    return {
      raw,
      terminal,
      accepted,
      phase: !terminal ? "RUNNING" : accepted ? "ACCEPTED" : "REJECTED",
      display,
    };
  }

  function matchesSubmission(job, submission) {
    const problem = submission.problem || {};
    const index = String(problem.index || "");
    if (!index || index.toLowerCase() !== String(job.problemIndex).toLowerCase()) return false;
    if (job.kind === "ACMSGURU") return true;
    return String(problem.contestId || "") === String(job.contestId);
  }

  function submissionPageUrl(submission) {
    const id = submission.id;
    const contestId = submission.problem && submission.problem.contestId;
    if (!id || !contestId) return "";
    return `https://codeforces.com/contest/${contestId}/submission/${id}`;
  }

  function match(text, re) {
    const m = re.exec(text || "");
    return m ? m[1] : "";
  }

  function stripTags(html) {
    return String(html || "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ");
  }

  const api = {
    isProblemUrl,
    postJsonWithTimeout,
    extractCsrf,
    extractHandle,
    extractSubmitError,
    verdictFromSubmission,
    matchesSubmission,
    submissionPageUrl,
  };

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  } else {
    root.CphSubmitCore = api;
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
