import { useState, useEffect, useCallback, useRef } from "react";
import axios from "axios";
import { formatDistanceToNow, format } from "date-fns";
import {
  Activity, Server, CheckCircle2, XCircle, Clock, AlertTriangle,
  RefreshCw, Send, Trash2, RotateCcw, ChevronDown, ChevronUp,
  Cpu, Zap, BarChart3, List, Settings, Terminal
} from "lucide-react";
import {
  LineChart, Line, AreaChart, Area, BarChart, Bar,
  XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell
} from "recharts";

const API = axios.create({ baseURL: process.env.REACT_APP_API_URL || "/api/v1" });

const STATUS_CONFIG = {
  PENDING:     { color: "#94a3b8", bg: "#1e293b", label: "Pending" },
  QUEUED:      { color: "#60a5fa", bg: "#1e3a5f", label: "Queued" },
  RUNNING:     { color: "#34d399", bg: "#064e3b", label: "Running" },
  COMPLETED:   { color: "#4ade80", bg: "#052e16", label: "Completed" },
  FAILED:      { color: "#f87171", bg: "#450a0a", label: "Failed" },
  CANCELLED:   { color: "#a78bfa", bg: "#2e1065", label: "Cancelled" },
  DEAD_LETTER: { color: "#fb923c", bg: "#431407", label: "Dead Letter" },
};

const PRIORITY_CONFIG = {
  LOW:      { color: "#64748b", label: "LOW" },
  NORMAL:   { color: "#3b82f6", label: "NORMAL" },
  HIGH:     { color: "#f59e0b", label: "HIGH" },
  CRITICAL: { color: "#ef4444", label: "CRITICAL" },
};

function StatusBadge({ status }) {
  const cfg = STATUS_CONFIG[status] || STATUS_CONFIG.PENDING;
  return (
    <span style={{
      background: cfg.bg, color: cfg.color,
      border: `1px solid ${cfg.color}40`,
      borderRadius: 3, padding: "2px 8px", fontSize: 11,
      fontFamily: "monospace", fontWeight: 700, letterSpacing: 1
    }}>
      {cfg.label}
    </span>
  );
}

function PriorityBadge({ priority }) {
  const cfg = PRIORITY_CONFIG[priority] || PRIORITY_CONFIG.NORMAL;
  return (
    <span style={{
      color: cfg.color, border: `1px solid ${cfg.color}60`,
      borderRadius: 3, padding: "1px 6px", fontSize: 10,
      fontFamily: "monospace", fontWeight: 700, letterSpacing: 1
    }}>
      {cfg.label}
    </span>
  );
}

function StatCard({ icon: Icon, label, value, color, sub }) {
  return (
    <div style={{
      background: "#0f172a", border: "1px solid #1e293b",
      borderLeft: `3px solid ${color}`, borderRadius: 4, padding: "16px 20px",
      display: "flex", alignItems: "center", gap: 16
    }}>
      <div style={{ background: `${color}15`, borderRadius: 4, padding: 10 }}>
        <Icon size={20} color={color} />
      </div>
      <div>
        <div style={{ color: "#475569", fontSize: 11, letterSpacing: 1, textTransform: "uppercase", fontFamily: "monospace" }}>{label}</div>
        <div style={{ color: "#f1f5f9", fontSize: 28, fontWeight: 700, fontFamily: "monospace", lineHeight: 1.2 }}>{value ?? "—"}</div>
        {sub && <div style={{ color: "#475569", fontSize: 11, fontFamily: "monospace" }}>{sub}</div>}
      </div>
    </div>
  );
}

function SubmitJobModal({ onClose, onSubmit }) {
  const [form, setForm] = useState({
    type: "data_processing", payload: '{"records": 500}',
    priority: "NORMAL", maxRetries: 3
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const types = ["data_processing", "email_notification", "report_generation", "file_sync", "database_migration"];

  const submit = async () => {
    setLoading(true); setError(null);
    try {
      let payload = form.payload;
      try { JSON.parse(payload); } catch { setError("Payload must be valid JSON"); setLoading(false); return; }
      await onSubmit({ ...form, payload });
      onClose();
    } catch (e) {
      setError(e.response?.data?.message || "Failed to submit job");
    } finally { setLoading(false); }
  };

  return (
    <div style={{ position: "fixed", inset: 0, background: "#000000cc", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000 }}>
      <div style={{ background: "#0f172a", border: "1px solid #334155", borderRadius: 8, width: 520, padding: 32 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 24 }}>
          <Terminal size={18} color="#60a5fa" />
          <span style={{ color: "#f1f5f9", fontFamily: "monospace", fontSize: 16, fontWeight: 700 }}>SUBMIT JOB</span>
        </div>

        {[
          { label: "JOB TYPE", key: "type", type: "select", options: types },
          { label: "PRIORITY", key: "priority", type: "select", options: ["LOW", "NORMAL", "HIGH", "CRITICAL"] },
          { label: "MAX RETRIES", key: "maxRetries", type: "number" },
        ].map(f => (
          <div key={f.key} style={{ marginBottom: 16 }}>
            <label style={{ color: "#64748b", fontSize: 11, fontFamily: "monospace", letterSpacing: 1, display: "block", marginBottom: 6 }}>{f.label}</label>
            {f.type === "select" ? (
              <select value={form[f.key]} onChange={e => setForm(p => ({ ...p, [f.key]: e.target.value }))}
                style={{ width: "100%", background: "#1e293b", border: "1px solid #334155", borderRadius: 4, color: "#f1f5f9", padding: "8px 12px", fontFamily: "monospace", fontSize: 13 }}>
                {f.options.map(o => <option key={o} value={o}>{o}</option>)}
              </select>
            ) : (
              <input type={f.type} value={form[f.key]} onChange={e => setForm(p => ({ ...p, [f.key]: e.target.value }))}
                style={{ width: "100%", background: "#1e293b", border: "1px solid #334155", borderRadius: 4, color: "#f1f5f9", padding: "8px 12px", fontFamily: "monospace", fontSize: 13, boxSizing: "border-box" }} />
            )}
          </div>
        ))}

        <div style={{ marginBottom: 20 }}>
          <label style={{ color: "#64748b", fontSize: 11, fontFamily: "monospace", letterSpacing: 1, display: "block", marginBottom: 6 }}>PAYLOAD (JSON)</label>
          <textarea value={form.payload} onChange={e => setForm(p => ({ ...p, payload: e.target.value }))} rows={4}
            style={{ width: "100%", background: "#1e293b", border: "1px solid #334155", borderRadius: 4, color: "#4ade80", padding: "8px 12px", fontFamily: "monospace", fontSize: 12, resize: "vertical", boxSizing: "border-box" }} />
        </div>

        {error && <div style={{ color: "#f87171", fontFamily: "monospace", fontSize: 12, marginBottom: 16, background: "#450a0a", padding: "8px 12px", borderRadius: 4 }}>ERROR: {error}</div>}

        <div style={{ display: "flex", gap: 10 }}>
          <button onClick={submit} disabled={loading}
            style={{ flex: 1, background: "#2563eb", color: "#fff", border: "none", borderRadius: 4, padding: "10px 20px", fontFamily: "monospace", fontSize: 13, fontWeight: 700, cursor: "pointer" }}>
            {loading ? "SUBMITTING..." : "▶ SUBMIT"}
          </button>
          <button onClick={onClose}
            style={{ background: "#1e293b", color: "#94a3b8", border: "1px solid #334155", borderRadius: 4, padding: "10px 20px", fontFamily: "monospace", fontSize: 13, cursor: "pointer" }}>
            CANCEL
          </button>
        </div>
      </div>
    </div>
  );
}

function JobRow({ job, onCancel, onRequeue }) {
  const [expanded, setExpanded] = useState(false);
  const cfg = STATUS_CONFIG[job.status] || {};

  return (
    <>
      <tr onClick={() => setExpanded(e => !e)} style={{ cursor: "pointer", borderBottom: "1px solid #1e293b" }}>
        <td style={{ padding: "10px 12px", fontFamily: "monospace", fontSize: 11, color: "#64748b" }}>{job.id.slice(0, 8)}…</td>
        <td style={{ padding: "10px 12px", fontFamily: "monospace", fontSize: 12, color: "#94a3b8" }}>{job.type}</td>
        <td style={{ padding: "10px 12px" }}><StatusBadge status={job.status} /></td>
        <td style={{ padding: "10px 12px" }}><PriorityBadge priority={job.priority} /></td>
        <td style={{ padding: "10px 12px", fontFamily: "monospace", fontSize: 11, color: "#475569" }}>
          {job.retryCount}/{job.maxRetries}
        </td>
        <td style={{ padding: "10px 12px", fontFamily: "monospace", fontSize: 11, color: "#475569" }}>
          {job.workerId ? job.workerId.slice(0, 12) + "…" : "—"}
        </td>
        <td style={{ padding: "10px 12px", fontFamily: "monospace", fontSize: 11, color: "#475569" }}>
          {job.createdAt ? formatDistanceToNow(new Date(job.createdAt), { addSuffix: true }) : "—"}
        </td>
        <td style={{ padding: "10px 12px" }}>
          <div style={{ display: "flex", gap: 6 }}>
            {["PENDING", "QUEUED"].includes(job.status) && (
              <button onClick={e => { e.stopPropagation(); onCancel(job.id); }}
                style={{ background: "#450a0a", border: "none", color: "#f87171", borderRadius: 3, padding: "3px 8px", cursor: "pointer", fontSize: 11 }}>
                <Trash2 size={12} />
              </button>
            )}
            {["FAILED", "DEAD_LETTER"].includes(job.status) && (
              <button onClick={e => { e.stopPropagation(); onRequeue(job.id); }}
                style={{ background: "#052e16", border: "none", color: "#4ade80", borderRadius: 3, padding: "3px 8px", cursor: "pointer", fontSize: 11 }}>
                <RotateCcw size={12} />
              </button>
            )}
            {expanded ? <ChevronUp size={14} color="#475569" /> : <ChevronDown size={14} color="#475569" />}
          </div>
        </td>
      </tr>
      {expanded && (
        <tr style={{ background: "#0a0f1e" }}>
          <td colSpan={8} style={{ padding: "12px 16px" }}>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 16, fontSize: 11, fontFamily: "monospace" }}>
              <div>
                <div style={{ color: "#475569", marginBottom: 4 }}>FULL ID</div>
                <div style={{ color: "#94a3b8", wordBreak: "break-all" }}>{job.id}</div>
              </div>
              <div>
                <div style={{ color: "#475569", marginBottom: 4 }}>PAYLOAD</div>
                <div style={{ color: "#4ade80", background: "#071c10", padding: "6px 8px", borderRadius: 3 }}>{job.payload || "null"}</div>
              </div>
              <div>
                <div style={{ color: "#475569", marginBottom: 4 }}>RESULT / ERROR</div>
                <div style={{ color: job.errorMessage ? "#f87171" : "#60a5fa", background: "#0f172a", padding: "6px 8px", borderRadius: 3 }}>
                  {job.errorMessage || job.result || "—"}
                </div>
              </div>
              {job.startedAt && <div><div style={{ color: "#475569", marginBottom: 4 }}>STARTED</div><div style={{ color: "#94a3b8" }}>{format(new Date(job.startedAt), "HH:mm:ss.SSS")}</div></div>}
              {job.completedAt && <div><div style={{ color: "#475569", marginBottom: 4 }}>COMPLETED</div><div style={{ color: "#94a3b8" }}>{format(new Date(job.completedAt), "HH:mm:ss.SSS")}</div></div>}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

export default function App() {
  const [tab, setTab] = useState("dashboard");
  const [stats, setStats] = useState(null);
  const [jobs, setJobs] = useState([]);
  const [workers, setWorkers] = useState([]);
  const [jobsPage, setJobsPage] = useState(0);
  const [jobsTotal, setJobsTotal] = useState(0);
  const [jobsFilter, setJobsFilter] = useState({ status: "", type: "" });
  const [showSubmit, setShowSubmit] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [history, setHistory] = useState([]);
  const [lastUpdated, setLastUpdated] = useState(null);
  const timerRef = useRef(null);

  const fetchAll = useCallback(async () => {
    setRefreshing(true);
    try {
      const [statsRes, jobsRes, workersRes] = await Promise.all([
        API.get("/jobs/stats"),
        API.get("/jobs", { params: { page: jobsPage, size: 20, sortBy: "createdAt", sortDir: "DESC", ...Object.fromEntries(Object.entries(jobsFilter).filter(([, v]) => v)) } }),
        API.get("/jobs/workers"),
      ]);
      setStats(statsRes.data);
      setJobs(jobsRes.data.content || []);
      setJobsTotal(jobsRes.data.totalElements || 0);
      setWorkers(workersRes.data || []);
      setLastUpdated(new Date());
      setHistory(h => {
        const s = statsRes.data;
        const entry = { t: format(new Date(), "HH:mm:ss"), completed: s.completedLastHour, failed: s.failedLastHour, running: s.runningJobs };
        return [...h.slice(-29), entry];
      });
    } catch (e) {
      console.error("Fetch error:", e);
    } finally { setRefreshing(false); }
  }, [jobsPage, jobsFilter]);

  useEffect(() => {
    fetchAll();
    timerRef.current = setInterval(fetchAll, 5000);
    return () => clearInterval(timerRef.current);
  }, [fetchAll]);

  const submitJob = async (data) => {
    await API.post("/jobs", data);
    fetchAll();
  };

  const cancelJob = async (id) => {
    await API.delete(`/jobs/${id}`);
    fetchAll();
  };

  const requeueJob = async (id) => {
    await API.post(`/jobs/${id}/requeue`);
    fetchAll();
  };

  const s = stats || {};

  return (
    <div style={{ minHeight: "100vh", background: "#050a14", color: "#f1f5f9", fontFamily: "'JetBrains Mono', 'Fira Code', monospace" }}>
      {/* Top Bar */}
      <div style={{ background: "#0a0f1e", borderBottom: "1px solid #1e293b", padding: "0 24px", display: "flex", alignItems: "center", gap: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "14px 0", marginRight: 32, borderRight: "1px solid #1e293b", paddingRight: 32 }}>
          <div style={{ width: 8, height: 8, borderRadius: "50%", background: "#4ade80", boxShadow: "0 0 8px #4ade80" }} />
          <span style={{ color: "#f1f5f9", fontWeight: 700, fontSize: 14, letterSpacing: 2 }}>TASKFLOW</span>
          <span style={{ color: "#334155", fontSize: 11 }}>v1.0.0</span>
        </div>

        {[
          { id: "dashboard", icon: BarChart3, label: "DASHBOARD" },
          { id: "jobs", icon: List, label: "JOBS" },
          { id: "workers", icon: Server, label: "WORKERS" },
        ].map(({ id, icon: Icon, label }) => (
          <button key={id} onClick={() => setTab(id)}
            style={{ background: "none", border: "none", color: tab === id ? "#60a5fa" : "#475569", padding: "16px 20px", cursor: "pointer", fontSize: 11, letterSpacing: 1, fontFamily: "monospace", borderBottom: tab === id ? "2px solid #60a5fa" : "2px solid transparent" }}>
            <Icon size={13} style={{ display: "inline", marginRight: 6 }} />{label}
          </button>
        ))}

        <div style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 12 }}>
          {lastUpdated && <span style={{ color: "#334155", fontSize: 10 }}>UPDATED {format(lastUpdated, "HH:mm:ss")}</span>}
          <button onClick={fetchAll} disabled={refreshing}
            style={{ background: "#1e293b", border: "1px solid #334155", color: "#94a3b8", borderRadius: 4, padding: "6px 12px", cursor: "pointer", fontSize: 11 }}>
            <RefreshCw size={12} style={{ display: "inline", marginRight: 5 }} />{refreshing ? "SYNC..." : "REFRESH"}
          </button>
          <button onClick={() => setShowSubmit(true)}
            style={{ background: "#2563eb", border: "none", color: "#fff", borderRadius: 4, padding: "7px 16px", cursor: "pointer", fontSize: 11, fontWeight: 700 }}>
            <Send size={12} style={{ display: "inline", marginRight: 5 }} />SUBMIT JOB
          </button>
        </div>
      </div>

      <div style={{ padding: 24 }}>
        {/* DASHBOARD TAB */}
        {tab === "dashboard" && (
          <>
            {/* Stat Cards */}
            <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 16, marginBottom: 24 }}>
              <StatCard icon={Activity} label="Total Jobs" value={s.totalJobs} color="#60a5fa" />
              <StatCard icon={Zap} label="Running" value={s.runningJobs} color="#34d399" sub="active now" />
              <StatCard icon={CheckCircle2} label="Completed" value={s.completedJobs} color="#4ade80" sub={`${s.completedLastHour} last hour`} />
              <StatCard icon={XCircle} label="Failed" value={s.failedJobs} color="#f87171" sub={`${s.failedLastHour} last hour`} />
            </div>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: 16, marginBottom: 24 }}>
              <StatCard icon={Clock} label="Queued" value={s.queuedJobs} color="#60a5fa" />
              <StatCard icon={AlertTriangle} label="Dead Letter" value={s.deadLetterJobs} color="#fb923c" />
              <StatCard icon={CheckCircle2} label="Success Rate" value={s.successRate != null ? `${s.successRate.toFixed(1)}%` : "—"} color="#4ade80" />
              <StatCard icon={Cpu} label="Avg Duration" value={s.avgCompletionTimeSeconds != null ? `${s.avgCompletionTimeSeconds.toFixed(1)}s` : "—"} color="#a78bfa" />
            </div>

            {/* Charts */}
            <div style={{ display: "grid", gridTemplateColumns: "2fr 1fr", gap: 20 }}>
              <div style={{ background: "#0f172a", border: "1px solid #1e293b", borderRadius: 6, padding: 20 }}>
                <div style={{ color: "#64748b", fontSize: 11, letterSpacing: 1, marginBottom: 16 }}>THROUGHPUT (LAST 30 POLLS)</div>
                <ResponsiveContainer width="100%" height={200}>
                  <AreaChart data={history}>
                    <defs>
                      <linearGradient id="grad1" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#4ade80" stopOpacity={0.15} />
                        <stop offset="95%" stopColor="#4ade80" stopOpacity={0} />
                      </linearGradient>
                      <linearGradient id="grad2" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#f87171" stopOpacity={0.15} />
                        <stop offset="95%" stopColor="#f87171" stopOpacity={0} />
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" />
                    <XAxis dataKey="t" stroke="#334155" tick={{ fill: "#475569", fontSize: 10 }} />
                    <YAxis stroke="#334155" tick={{ fill: "#475569", fontSize: 10 }} />
                    <Tooltip contentStyle={{ background: "#1e293b", border: "1px solid #334155", borderRadius: 4, fontFamily: "monospace", fontSize: 11 }} />
                    <Area type="monotone" dataKey="completed" stroke="#4ade80" fill="url(#grad1)" strokeWidth={2} name="completed" />
                    <Area type="monotone" dataKey="failed" stroke="#f87171" fill="url(#grad2)" strokeWidth={2} name="failed" />
                  </AreaChart>
                </ResponsiveContainer>
              </div>

              <div style={{ background: "#0f172a", border: "1px solid #1e293b", borderRadius: 6, padding: 20 }}>
                <div style={{ color: "#64748b", fontSize: 11, letterSpacing: 1, marginBottom: 16 }}>JOB STATUS BREAKDOWN</div>
                <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                  {Object.entries(STATUS_CONFIG).map(([k, cfg]) => {
                    const val = s[k.toLowerCase() + "Jobs"] ?? s[k.toLowerCase().replace("_", "") + "Jobs"] ?? 0;
                    const total = s.totalJobs || 1;
                    const pct = Math.round((val / total) * 100);
                    return (
                      <div key={k}>
                        <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
                          <span style={{ color: cfg.color, fontSize: 10, fontFamily: "monospace" }}>{cfg.label}</span>
                          <span style={{ color: "#475569", fontSize: 10, fontFamily: "monospace" }}>{val}</span>
                        </div>
                        <div style={{ height: 4, background: "#1e293b", borderRadius: 2 }}>
                          <div style={{ height: 4, width: `${pct}%`, background: cfg.color, borderRadius: 2, transition: "width 0.5s" }} />
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          </>
        )}

        {/* JOBS TAB */}
        {tab === "jobs" && (
          <div>
            {/* Filters */}
            <div style={{ display: "flex", gap: 12, marginBottom: 16, alignItems: "center" }}>
              <select value={jobsFilter.status} onChange={e => { setJobsFilter(f => ({ ...f, status: e.target.value })); setJobsPage(0); }}
                style={{ background: "#1e293b", border: "1px solid #334155", color: "#94a3b8", borderRadius: 4, padding: "7px 12px", fontFamily: "monospace", fontSize: 12 }}>
                <option value="">ALL STATUS</option>
                {Object.keys(STATUS_CONFIG).map(s => <option key={s} value={s}>{s}</option>)}
              </select>
              <select value={jobsFilter.type} onChange={e => { setJobsFilter(f => ({ ...f, type: e.target.value })); setJobsPage(0); }}
                style={{ background: "#1e293b", border: "1px solid #334155", color: "#94a3b8", borderRadius: 4, padding: "7px 12px", fontFamily: "monospace", fontSize: 12 }}>
                <option value="">ALL TYPES</option>
                {["data_processing", "email_notification", "report_generation", "file_sync", "database_migration"].map(t => <option key={t} value={t}>{t}</option>)}
              </select>
              <span style={{ color: "#334155", fontSize: 11, fontFamily: "monospace", marginLeft: "auto" }}>{jobsTotal} TOTAL JOBS</span>
            </div>

            <div style={{ background: "#0f172a", border: "1px solid #1e293b", borderRadius: 6, overflow: "hidden" }}>
              <table style={{ width: "100%", borderCollapse: "collapse" }}>
                <thead>
                  <tr style={{ background: "#0a0f1e" }}>
                    {["ID", "TYPE", "STATUS", "PRIORITY", "RETRIES", "WORKER", "AGE", "ACTIONS"].map(h => (
                      <th key={h} style={{ padding: "10px 12px", textAlign: "left", color: "#475569", fontSize: 10, fontFamily: "monospace", letterSpacing: 1, borderBottom: "1px solid #1e293b" }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {jobs.length === 0 ? (
                    <tr><td colSpan={8} style={{ padding: 40, textAlign: "center", color: "#334155", fontFamily: "monospace" }}>NO JOBS FOUND</td></tr>
                  ) : jobs.map(job => (
                    <JobRow key={job.id} job={job} onCancel={cancelJob} onRequeue={requeueJob} />
                  ))}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
            <div style={{ display: "flex", justifyContent: "center", gap: 8, marginTop: 16 }}>
              <button disabled={jobsPage === 0} onClick={() => setJobsPage(p => p - 1)}
                style={{ background: "#1e293b", border: "1px solid #334155", color: jobsPage === 0 ? "#334155" : "#94a3b8", borderRadius: 4, padding: "6px 14px", cursor: jobsPage === 0 ? "not-allowed" : "pointer", fontFamily: "monospace", fontSize: 11 }}>
                ◀ PREV
              </button>
              <span style={{ color: "#475569", fontSize: 11, fontFamily: "monospace", padding: "6px 12px" }}>
                PAGE {jobsPage + 1} / {Math.ceil(jobsTotal / 20) || 1}
              </span>
              <button disabled={jobs.length < 20} onClick={() => setJobsPage(p => p + 1)}
                style={{ background: "#1e293b", border: "1px solid #334155", color: jobs.length < 20 ? "#334155" : "#94a3b8", borderRadius: 4, padding: "6px 14px", cursor: jobs.length < 20 ? "not-allowed" : "pointer", fontFamily: "monospace", fontSize: 11 }}>
                NEXT ▶
              </button>
            </div>
          </div>
        )}

        {/* WORKERS TAB */}
        {tab === "workers" && (
          <div>
            <div style={{ color: "#64748b", fontSize: 11, letterSpacing: 1, marginBottom: 16 }}>{workers.length} ACTIVE WORKERS</div>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(320px, 1fr))", gap: 16 }}>
              {workers.length === 0 ? (
                <div style={{ color: "#334155", fontFamily: "monospace", padding: 40 }}>NO ACTIVE WORKERS — START PYTHON WORKERS TO SEE THEM HERE</div>
              ) : workers.map(w => (
                <div key={w.workerId} style={{ background: "#0f172a", border: "1px solid #1e293b", borderLeft: "3px solid #34d399", borderRadius: 6, padding: 20 }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 16 }}>
                    <div style={{ width: 8, height: 8, borderRadius: "50%", background: "#4ade80", boxShadow: "0 0 6px #4ade80" }} />
                    <span style={{ color: "#f1f5f9", fontFamily: "monospace", fontSize: 13, fontWeight: 700 }}>{w.workerId}</span>
                  </div>
                  {[
                    { label: "STATUS", value: w.status, color: "#4ade80" },
                    { label: "ACTIVE JOBS", value: w.activeJobs, color: "#60a5fa" },
                    { label: "PROCESSED", value: w.processedJobs, color: "#a78bfa" },
                    { label: "FAILED", value: w.failedJobs, color: "#f87171" },
                    { label: "LAST SEEN", value: w.lastSeen ? formatDistanceToNow(new Date(w.lastSeen), { addSuffix: true }) : "—", color: "#64748b" },
                  ].map(({ label, value, color }) => (
                    <div key={label} style={{ display: "flex", justifyContent: "space-between", marginBottom: 8 }}>
                      <span style={{ color: "#475569", fontSize: 11, fontFamily: "monospace" }}>{label}</span>
                      <span style={{ color, fontSize: 11, fontFamily: "monospace", fontWeight: 700 }}>{value}</span>
                    </div>
                  ))}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {showSubmit && <SubmitJobModal onClose={() => setShowSubmit(false)} onSubmit={submitJob} />}
    </div>
  );
}
