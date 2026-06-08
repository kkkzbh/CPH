(function (root) {
  if (root.__cphPageSubmitInstalled) return;
  root.__cphPageSubmitInstalled = true;

  const INLINE_VERIFICATION_TIMEOUT_MS = 60000;
  const INLINE_VERIFICATION_POLL_MS = 250;
  const SUBMIT_NAVIGATION_DELAY_MS = 150;

  root.addEventListener("message", (event) => {
    if (event.source !== root) return;
    const msg = event.data;
    if (!msg || msg.type !== "CPH_SUBMIT_JOB" || !msg.requestId || !msg.job) return;
    run(msg.job)
      .then((result) => {
        root.postMessage({ type: "CPH_SUBMIT_RESULT", requestId: msg.requestId, ok: true, result }, "*");
      })
      .catch((err) => {
        root.postMessage({
          type: "CPH_SUBMIT_RESULT",
          requestId: msg.requestId,
          ok: false,
          error: err && err.message ? err.message : String(err),
        }, "*");
      });
  });

  root.CphPageSubmit = { run };

  async function run(job) {
    if (!/\/submit(?:[?#]|$)/.test(root.location.pathname + root.location.search)) {
      throw new Error("CPH Target Runner must run on the Codeforces Submit Code page");
    }
    if (/\/enter\?back=|handleOrEmail|password/i.test(document.documentElement.innerHTML)) {
      throw new Error("please log in to Codeforces in this browser");
    }
    if (isFullPageCloudflareChallenge()) {
      throw new Error("Codeforces Cloudflare challenge is blocking the Submit Code page");
    }

    const form = await waitForSubmitForm();
    const handle = CphSubmitCore.extractHandle(document.documentElement.outerHTML) || await fetchCurrentHandle();
    if (!handle) throw new Error("could not detect the logged-in Codeforces handle");

    const beforeMaxId = await latestSubmissionId(handle);
    const startedAtSeconds = Math.floor(Date.now() / 1000);
    const debug = await fillSubmitCodeForm(form, job);
    root.postMessage({ type: "CPH_SUBMIT_PROGRESS", debug }, "*");

    await waitForInlineCloudflareVerification(form);
    await waitForSubmitButtonReady(form);
    revalidateSubmitCodeForm(form, job);
    submitPreparedForm(form);
    return {
      handle,
      beforeMaxId,
      startedAtSeconds,
      pageUrl: root.location.href,
      debug,
    };
  }

  function waitForSubmitForm() {
    const existing = findSubmitCodeForm();
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const started = Date.now();
      const timer = setInterval(() => {
        const form = findSubmitCodeForm();
        if (form) {
          clearInterval(timer);
          resolve(form);
        } else if (isFullPageCloudflareChallenge()) {
          clearInterval(timer);
          reject(new Error("Codeforces Cloudflare challenge is blocking the Submit Code page"));
        } else if (Date.now() - started > 15000) {
          clearInterval(timer);
          reject(new Error("could not find the Codeforces Submit Code form"));
        }
      }, 100);
    });
  }

  function findSubmitCodeForm() {
    return Array.from(document.forms).find((form) =>
      form.querySelector('[name="programTypeId"]') &&
      form.querySelector('[name="source"]') &&
      (form.querySelector('[name="submittedProblemIndex"]') || form.querySelector('[name="submittedProblemCode"]'))
    );
  }

  function isFullPageCloudflareChallenge() {
    return isFullPageCloudflareChallengeHtml(document.documentElement.outerHTML);
  }

  function isFullPageCloudflareChallengeHtml(html) {
    return /Just a moment|cf-mitigated|cf_chl|challenge-platform|Enable JavaScript and cookies to continue/i.test(html);
  }

  async function fillSubmitCodeForm(form, job) {
    setField(form, "action", "submitSolutionFormSubmitted", false);
    setProblem(form, job);
    setProgramType(form, job.programTypeId);
    setSource(form, job.source);
    setField(form, "tabSize", getField(form, "tabSize") || "4", false);
    if (form.elements.ftaa && root._ftaa) setField(form, "ftaa", root._ftaa, false);
    if (form.elements.bfaa && root._bfaa) setField(form, "bfaa", root._bfaa, false);
    if (form.elements._tta && root.Codeforces && typeof root.Codeforces.tta === "function") {
      setField(form, "_tta", root.Codeforces.tta(), false);
    }

    return [
      `Submit Code page ready`,
      `problem=${submittedProblemFieldValue(job)}`,
      `lang=${getField(form, "programTypeId")}`,
      `sourceBytes=${new Blob([job.source]).size}`,
      `action=${form.getAttribute("action")}`,
    ].join(", ");
  }

  function setProblem(form, job) {
    const preferredName = submittedProblemFieldName(job.kind);
    const field = form.elements[preferredName] || form.elements.submittedProblemIndex || form.elements.submittedProblemCode;
    if (!field) throw new Error("Codeforces Submit Code page has no problem selector");
    const value = submittedProblemFieldValue(job);
    setFieldElement(field, value, true);
    if (String(field.value).toLowerCase() !== String(value).toLowerCase()) {
      throw new Error(`Codeforces Submit Code page does not list problem ${value}`);
    }
  }

  function setProgramType(form, programTypeId) {
    const field = form.elements.programTypeId;
    if (!field) throw new Error("Codeforces Submit Code page has no language selector");
    const value = String(programTypeId);
    setFieldElement(field, value, true);
    if (String(field.value) !== value) {
      const options = Array.from(field.options || []).map((option) => `${option.value}:${option.textContent.trim()}`).join(", ");
      throw new Error(`Codeforces Submit Code page does not list language id ${value}; available: ${options}`);
    }
  }

  function setSource(form, source) {
    const sourceText = source == null ? "" : String(source);
    const textarea = form.elements.source || document.querySelector("#sourceCodeTextarea");
    if (!textarea) throw new Error("Codeforces Submit Code page has no source field");
    if (root.ace && document.querySelector("#editor")) {
      const editor = root.ace.edit("editor");
      editor.setValue(sourceText, -1);
      editor.clearSelection();
    }
    setFieldElement(textarea, sourceText, true);
    if (textarea.value !== sourceText) {
      throw new Error("Codeforces source field did not accept the current editor text");
    }
  }

  function setField(form, name, value, notify) {
    const field = form.elements[name];
    if (!field) return false;
    setFieldElement(field, value, notify);
    return true;
  }

  function setFieldElement(field, value, notify) {
    field.value = value == null ? "" : String(value);
    if (notify) {
      field.dispatchEvent(new Event("input", { bubbles: true }));
      field.dispatchEvent(new Event("change", { bubbles: true }));
    }
  }

  function getField(form, name) {
    const field = form.elements[name];
    return field ? field.value || "" : "";
  }

  function findSubmitButton(form) {
    return form.querySelector("#singlePageSubmitButton, input[type='submit'], button[type='submit']");
  }

  async function waitForSubmitButtonReady(form) {
    const started = Date.now();
    let button = findSubmitButton(form);
    while (!button || button.disabled) {
      if (Date.now() - started > 5000) {
        if (!button) throw new Error("could not find the Submit Code button");
        throw new Error("Codeforces Submit Code button is still disabled after filling the form");
      }
      await sleep(50);
      button = findSubmitButton(form);
    }
    return button;
  }

  async function waitForInlineCloudflareVerification(form) {
    const started = Date.now();
    while (true) {
      const state = inlineCloudflareVerificationState(form);
      if (state === "absent") return;
      if (state === "complete") return;
      if (Date.now() - started > INLINE_VERIFICATION_TIMEOUT_MS) {
        throw new Error("Codeforces inline Cloudflare verification did not complete");
      }
      await sleep(INLINE_VERIFICATION_POLL_MS);
    }
  }

  function inlineCloudflareVerificationState(form) {
    const html = document.documentElement.outerHTML;
    if (CphSubmitCore.inlineCloudflareChallengeCompleted(html)) {
      return CphSubmitCore.hasInlineCloudflareChallenge(html) ? "complete" : "absent";
    }
    if (turnstileResponseFields().some((field) => String(field.value || "").trim())) {
      return "complete";
    }
    if (hasInlineCloudflareWidget(form) || CphSubmitCore.hasInlineCloudflareChallenge(html)) {
      return "pending";
    }
    return "absent";
  }

  function hasInlineCloudflareWidget(form) {
    return inlineCloudflareNodes(form).length > 0 || inlineCloudflareNodes(document).length > 0;
  }

  function inlineCloudflareNodes(scope) {
    const rootNode = scope || document;
    return Array.from(rootNode.querySelectorAll([
      ".cf-turnstile",
      "[data-cf-challenge]",
      "[name='cf-turnstile-response']",
      "iframe[src*='challenges.cloudflare.com']",
      "iframe[src*='turnstile']",
    ].join(",")));
  }

  function turnstileResponseFields() {
    return Array.from(document.querySelectorAll([
      "input[name='cf-turnstile-response']",
      "textarea[name='cf-turnstile-response']",
      "input[name='turnstileToken']",
    ].join(",")));
  }

  function revalidateSubmitCodeForm(form, job) {
    if (!document.documentElement.contains(form)) {
      throw new Error("Codeforces Submit Code form disappeared before submit");
    }
    const expectedProblem = submittedProblemFieldValue(job);
    const problemField = form.elements[submittedProblemFieldName(job.kind)] ||
      form.elements.submittedProblemIndex ||
      form.elements.submittedProblemCode;
    if (!problemField || String(problemField.value).toLowerCase() !== String(expectedProblem).toLowerCase()) {
      throw new Error(`Codeforces Submit Code page changed problem before submit: expected ${expectedProblem}`);
    }
    if (String(getField(form, "programTypeId")) !== String(job.programTypeId)) {
      throw new Error("Codeforces Submit Code page changed language before submit");
    }
    const sourceField = form.elements.source || document.querySelector("#sourceCodeTextarea");
    const sourceText = job.source == null ? "" : String(job.source);
    if (!sourceField || sourceField.value !== sourceText) {
      throw new Error("Codeforces Submit Code page changed source before submit");
    }
  }

  function submitPreparedForm(form) {
    refreshSubmitFrameFields(form);
    removeEmptySourceFileEncoding(form);
    root.setTimeout(() => form.submit(), SUBMIT_NAVIGATION_DELAY_MS);
  }

  function refreshSubmitFrameFields(form) {
    if (form.elements.ftaa && root._ftaa) setField(form, "ftaa", root._ftaa, false);
    if (form.elements.bfaa && root._bfaa) setField(form, "bfaa", root._bfaa, false);
    if (form.elements._tta && root.Codeforces && typeof root.Codeforces.tta === "function") {
      setField(form, "_tta", root.Codeforces.tta(), false);
    }
  }

  function removeEmptySourceFileEncoding(form) {
    if (form.getAttribute("enctype") !== "multipart/form-data") return;
    const sourceFiles = form.querySelectorAll(".table-form input[name='sourceFile']");
    if (sourceFiles.length === 1 && sourceFiles[0].files && sourceFiles[0].files.length === 0) {
      form.removeAttribute("enctype");
    }
  }

  function submittedProblemFieldName(kind) {
    return kind === "PROBLEMSET" || kind === "ACMSGURU" ? "submittedProblemCode" : "submittedProblemIndex";
  }

  function submittedProblemFieldValue(job) {
    if (job.kind === "PROBLEMSET") return `${job.contestId}${job.problemIndex}`;
    if (job.kind === "ACMSGURU") return job.problemIndex;
    return job.problemIndex;
  }

  async function fetchCurrentHandle() {
    const home = await fetch("https://codeforces.com/", { credentials: "include", cache: "no-store" });
    if (!home.ok) throw new Error(`Codeforces home failed: HTTP ${home.status}`);
    return CphSubmitCore.extractHandle(await home.text());
  }

  async function latestSubmissionId(handle) {
    const url = `https://codeforces.com/api/user.status?handle=${encodeURIComponent(handle)}&from=1&count=1`;
    const resp = await fetch(url, { credentials: "include", cache: "no-store" });
    if (!resp.ok) return 0;
    const root = await resp.json();
    const first = root && root.status === "OK" && Array.isArray(root.result) ? root.result[0] : null;
    return first ? Number(first.id || 0) : 0;
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
})(globalThis);
