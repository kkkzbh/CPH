import {
  BookOpen,
  CheckCircle2,
  Clipboard,
  ClipboardCheck,
  Menu,
  PanelLeft,
  Play,
  Search,
  Settings,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import type { Components } from "react-markdown";
import rehypeHighlight from "rehype-highlight";
import rehypeRaw from "rehype-raw";
import remarkGfm from "remark-gfm";
import { docPages, groups, type DocGroup, type DocPage } from "./content";

interface HeadingItem {
  id: string;
  text: string;
  level: number;
}

interface LightboxImage {
  src: string;
  alt: string;
}

const groupIcons: Record<DocGroup, typeof BookOpen> = {
  开始: BookOpen,
  工作流: Play,
  配置: Settings,
  排错: CheckCircle2,
};

function parseHash(): string {
  const raw = window.location.hash.replace(/^#\/?/, "");
  return raw || docPages[0].id;
}

function slugify(text: string): string {
  return text
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, "")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-") || "section";
}

function extractText(value: unknown): string {
  if (typeof value === "string" || typeof value === "number") return String(value);
  if (Array.isArray(value)) return value.map(extractText).join("");
  if (value && typeof value === "object" && "props" in value) {
    return extractText((value as { props?: { children?: unknown } }).props?.children);
  }
  return "";
}

function extractCode(value: unknown): string {
  return extractText(value).replace(/\n$/, "");
}

function headingsFrom(markdown: string): HeadingItem[] {
  return markdown
    .split("\n")
    .map((line) => /^(#{2,3})\s+(.+)$/.exec(line.trim()))
    .filter((match): match is RegExpExecArray => Boolean(match))
    .map((match) => ({
      id: slugify(match[2]),
      text: match[2].replace(/[*_`]/g, ""),
      level: match[1].length,
    }));
}

function App() {
  const [activeId, setActiveId] = useState(parseHash);
  const [query, setQuery] = useState("");
  const [activeHeading, setActiveHeading] = useState("");
  const [copiedCode, setCopiedCode] = useState("");
  const [lightbox, setLightbox] = useState<LightboxImage | null>(null);
  const [navOpen, setNavOpen] = useState(false);
  const articleRef = useRef<HTMLElement | null>(null);

  const activePage = useMemo(
    () => docPages.find((page) => page.id === activeId) ?? docPages[0],
    [activeId],
  );

  const filteredPages = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return docPages;
    return docPages.filter((page) => {
      const haystack = `${page.title} ${page.description} ${page.markdown}`.toLowerCase();
      return haystack.includes(needle);
    });
  }, [query]);

  const headings = useMemo(() => headingsFrom(activePage.markdown), [activePage]);

  useEffect(() => {
    const onHash = () => setActiveId(parseHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  useEffect(() => {
    document.title = `${activePage.title} - CPH Target Runner Docs`;
    setActiveHeading(headings[0]?.id ?? "");
    window.scrollTo({ top: 0, behavior: "smooth" });
  }, [activePage, headings]);

  useEffect(() => {
    const progress = document.querySelector<HTMLElement>(".read-progress");
    const update = () => {
      const max = document.documentElement.scrollHeight - window.innerHeight;
      const pct = max <= 0 ? 0 : Math.min(1, window.scrollY / max);
      if (progress) progress.style.transform = `scaleX(${pct})`;
    };
    update();
    window.addEventListener("scroll", update, { passive: true });
    window.addEventListener("resize", update);
    return () => {
      window.removeEventListener("scroll", update);
      window.removeEventListener("resize", update);
    };
  }, [activePage]);

  useEffect(() => {
    const nodes = headings
      .map((heading) => document.getElementById(heading.id))
      .filter((node): node is HTMLElement => Boolean(node));
    if (!nodes.length) return;

    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
        if (visible?.target.id) setActiveHeading(visible.target.id);
      },
      { rootMargin: "-18% 0px -62% 0px", threshold: [0, 1] },
    );

    nodes.forEach((node) => observer.observe(node));
    return () => observer.disconnect();
  }, [activePage, headings]);

  const selectPage = (page: DocPage) => {
    setActiveId(page.id);
    setNavOpen(false);
    window.history.replaceState(null, "", `#/${page.id}`);
  };

  const scrollToHeading = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
    setActiveHeading(id);
  };

  const components: Components = {
    h1({ children }) {
      return <h1>{children}</h1>;
    },
    h2({ children }) {
      const id = slugify(extractText(children));
      return (
        <h2 id={id}>
          {children}
          <button className="heading-anchor" type="button" onClick={() => scrollToHeading(id)}>
            #
          </button>
        </h2>
      );
    },
    h3({ children }) {
      const id = slugify(extractText(children));
      return (
        <h3 id={id}>
          {children}
          <button className="heading-anchor" type="button" onClick={() => scrollToHeading(id)}>
            #
          </button>
        </h3>
      );
    },
    pre({ children }) {
      const code = extractCode(children);
      const copied = copiedCode === code;
      return (
        <div className="code-block">
          <button
            className="copy-button"
            type="button"
            onClick={() => {
              void navigator.clipboard.writeText(code).then(() => {
                setCopiedCode(code);
                window.setTimeout(() => setCopiedCode(""), 1200);
              });
            }}
          >
            {copied ? <ClipboardCheck size={15} /> : <Clipboard size={15} />}
            {copied ? "Copied" : "Copy"}
          </button>
          <pre>{children}</pre>
        </div>
      );
    },
    img({ src, alt }) {
      if (!src) return null;
      const isQuickStartImage = src.includes("/assets/quick-start-");
      return (
        <button
          className={`markdown-image ${isQuickStartImage ? "is-quick-start" : ""}`}
          type="button"
          onClick={() => setLightbox({ src, alt: alt ?? "" })}
        >
          <img src={src} alt={alt ?? ""} loading="lazy" />
          {alt ? <span>{alt}</span> : null}
        </button>
      );
    },
    a({ href, children }) {
      return (
        <a href={href} target={href?.startsWith("http") ? "_blank" : undefined} rel="noreferrer">
          {children}
        </a>
      );
    },
  };

  return (
    <>
      <div className="read-progress" />
      {navOpen ? (
        <button
          className="nav-scrim"
          type="button"
          aria-label="关闭侧栏"
          onClick={() => setNavOpen(false)}
        />
      ) : null}
      <button
        className="floating-menu"
        type="button"
        aria-label={navOpen ? "关闭侧栏" : "打开侧栏"}
        onClick={() => setNavOpen((open) => !open)}
      >
        {navOpen ? <X size={18} /> : <Menu size={18} />}
      </button>

      <div className="docs-layout">
        <aside className={`left-nav ${navOpen ? "is-open" : ""}`}>
          <a className="sidebar-brand" href="#/">
            <span className="sidebar-brand-mark">
              <img src="/assets/plugin-icon.png" alt="" />
            </span>
            <span className="sidebar-brand-text">CPH Docs</span>
          </a>
          <div className="search-box">
            <Search size={15} />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索文档"
              aria-label="搜索文档"
            />
          </div>
          <nav aria-label="文档章节">
            {groups.map((group) => {
              const Icon = groupIcons[group];
              const pages = filteredPages.filter((page) => page.group === group);
              if (!pages.length) return null;
              return (
                <section className="nav-group" key={group}>
                  <div className="nav-group-title">
                    <Icon size={13} />
                    {group}
                  </div>
                  {pages.map((page) => {
                    const isActive = page.id === activePage.id;
                    return (
                      <button
                        className={`doc-tab ${isActive ? "is-active" : ""}`}
                        key={page.id}
                        type="button"
                        onClick={() => selectPage(page)}
                      >
                        <span>{page.title}</span>
                        {isActive ? <small>{page.description}</small> : null}
                      </button>
                    );
                  })}
                </section>
              );
            })}
          </nav>
        </aside>

        <main className="content-stage">
          <article className="reader" ref={articleRef} key={activePage.id}>
            <div className="doc-kicker">
              <PanelLeft size={14} />
              {activePage.group}
            </div>
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              rehypePlugins={[rehypeRaw, rehypeHighlight]}
              components={components}
            >
              {activePage.markdown}
            </ReactMarkdown>
          </article>
        </main>

        <aside className="right-toc" aria-label="当前章节目录">
          <div className="toc-shell">
            <p>本页目录</p>
            {headings.length ? (
              <nav>
                {headings.map((heading) => (
                  <button
                    className={`toc-link level-${heading.level} ${
                      heading.id === activeHeading ? "is-active" : ""
                    }`}
                    key={`${activePage.id}-${heading.id}`}
                    type="button"
                    onClick={() => scrollToHeading(heading.id)}
                  >
                    {heading.text}
                  </button>
                ))}
              </nav>
            ) : (
              <span className="toc-empty">当前页没有二级标题</span>
            )}
          </div>
        </aside>
      </div>

      {lightbox ? (
        <div className="lightbox" role="dialog" aria-modal="true" onClick={() => setLightbox(null)}>
          <button className="lightbox-close" type="button" aria-label="关闭图片预览">
            <X size={20} />
          </button>
          <img src={lightbox.src} alt={lightbox.alt} />
          {lightbox.alt ? <p>{lightbox.alt}</p> : null}
        </div>
      ) : null}
    </>
  );
}

export default App;
