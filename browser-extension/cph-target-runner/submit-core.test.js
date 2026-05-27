const assert = require("assert");
const core = require("./submit-core.js");

assert.strictEqual(core.isProblemUrl("https://codeforces.com/contest/1/problem/A"), true);
assert.strictEqual(core.isProblemUrl("https://codeforces.com/problemset/problem/4/B"), true);
assert.strictEqual(core.isProblemUrl("https://codeforces.com/gym/100123/problem/C"), true);
assert.strictEqual(core.isProblemUrl("https://codeforces.com/blog/entry/1"), false);

const html = `
  <html><head><meta name="X-Csrf-Token" content="abcdef123456"></head>
  <body>
    <a href="/profile/tourist" class="lang-chooser-link">tourist</a>
    <form><input type="hidden" name="ftaa" value="f"><input name="_tta" value="7"></form>
  </body></html>
`;
assert.strictEqual(core.extractCsrf(html), "abcdef123456");
assert.strictEqual(core.extractHandle(html), "tourist");

const job = {
  kind: "PROBLEMSET",
  contestId: "4",
  problemIndex: "B",
};
assert.strictEqual(core.extractSubmitError('<span class="error for__source">Source should not exceed 65536 bytes</span>'), "Source should not exceed 65536 bytes");
assert.strictEqual(core.extractSubmitError('<div class="alert alert-danger">Rejected by Codeforces</div>'), "Rejected by Codeforces");
assert.strictEqual(core.extractSubmitError("You have submitted exactly the same code before"), "identical to a previous submission");

assert.strictEqual(core.hasInlineCloudflareChallenge('<div class="cf-turnstile"></div>'), true);
assert.strictEqual(core.hasInlineCloudflareChallenge('<iframe src="https://challenges.cloudflare.com/turnstile/v0/api.js"></iframe>'), true);
assert.strictEqual(core.hasInlineCloudflareChallenge('<form><textarea name="source"></textarea></form>'), false);
assert.strictEqual(core.inlineCloudflareChallengeCompleted('<div class="cf-turnstile"></div><input name="cf-turnstile-response" value="">'), false);
assert.strictEqual(
  core.inlineCloudflareChallengeCompleted('<div class="cf-turnstile"></div><input name="cf-turnstile-response" value="token">'),
  true,
);

const running = core.verdictFromSubmission({ verdict: "TESTING", passedTestCount: 2 });
assert.strictEqual(running.phase, "RUNNING");
assert.strictEqual(running.display, "Running on test 3");

const accepted = core.verdictFromSubmission({
  verdict: "OK",
  passedTestCount: 12,
  timeConsumedMillis: 46,
  memoryConsumedBytes: 327680,
});
assert.strictEqual(accepted.phase, "ACCEPTED");
assert.strictEqual(accepted.display, "Accepted · 46 ms · 320 KB");

const wa = core.verdictFromSubmission({ verdict: "WRONG_ANSWER", passedTestCount: 4, timeConsumedMillis: 31 });
assert.strictEqual(wa.phase, "REJECTED");
assert.strictEqual(wa.display, "Wrong answer on test 5 · 31 ms");

assert.strictEqual(core.matchesSubmission(job, { problem: { contestId: 4, index: "B" } }), true);
assert.strictEqual(core.matchesSubmission(job, { problem: { contestId: 5, index: "B" } }), false);

console.log("submit-core tests passed");
