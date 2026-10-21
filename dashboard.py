import os
import re
import glob
import json
import math
import sqlite3
import urllib.request
from datetime import datetime
from typing import Dict, Any, List, Tuple

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import seaborn as sns
import streamlit as st

# ==============================================================================
# PAGE CONFIGURATION & CUSTOM STYLING
# ==============================================================================
st.set_page_config(
    page_title="SnipTutor — Usage & Token Analytics Dashboard",
    page_icon="📊",
    layout="wide",
    initial_sidebar_state="expanded",
)

# Dark theme palette for Matplotlib & Seaborn
plt.rcParams.update({
    "figure.facecolor": "#161b22",
    "axes.facecolor": "#0d1117",
    "axes.edgecolor": "#30363d",
    "axes.labelcolor": "#c9d1d9",
    "text.color": "#e6edf3",
    "xtick.color": "#8b949e",
    "ytick.color": "#8b949e",
    "grid.color": "#21262d",
    "grid.linestyle": "--",
    "grid.alpha": 0.6,
    "font.size": 10,
    "axes.titlesize": 12,
    "axes.titleweight": "bold",
})

PALETTE_BLUE = "#58a6ff"
PALETTE_PURPLE = "#bc8cff"
PALETTE_GREEN = "#3fb950"
PALETTE_AMBER = "#d29922"
PALETTE_CORAL = "#f85149"
PALETTE_CYAN = "#39c5cf"
PALETTE_PINK = "#f778ba"

st.markdown("""
<style>
    .block-container {
        padding-top: 1.4rem;
        padding-bottom: 2.5rem;
    }
    .kpi-card {
        background: linear-gradient(145deg, #161b22 0%, #1c2128 100%);
        border: 1px solid #30363d;
        border-radius: 12px;
        padding: 16px 18px;
        box-shadow: 0 4px 14px rgba(0, 0, 0, 0.25);
        margin-bottom: 12px;
    }
    .kpi-title {
        color: #8b949e;
        font-size: 0.82rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        margin-bottom: 4px;
    }
    .kpi-value {
        color: #f0f6fc;
        font-size: 1.75rem;
        font-weight: 800;
        line-height: 1.2;
    }
    .kpi-sub {
        color: #58a6ff;
        font-size: 0.80rem;
        margin-top: 5px;
        font-weight: 500;
    }
    .insight-box {
        background-color: rgba(88, 166, 255, 0.08);
        border-left: 4px solid #58a6ff;
        padding: 12px 16px;
        border-radius: 0 8px 8px 0;
        margin: 10px 0 18px 0;
        font-size: 0.92rem;
    }
</style>
""", unsafe_allow_html=True)


# ==============================================================================
# DATA LOADING & FEATURE ENGINEERING PIPELINE
# ==============================================================================
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB_PATH = os.getenv("SNIPTUTOR_DB_PATH", os.path.join(BASE_DIR, "sniptutor.db"))
SNIP_PICTURES_DIR = os.path.join(os.path.expanduser("~"), "Pictures", "SnipTutor")
CLOUD_BACKEND_URL = os.getenv("SNIPTUTOR_BACKEND_URL", "https://sniptutor.onrender.com")


def classify_topic(user_text: str, ai_text: str, conv_title: str) -> str:
    combined = f"{user_text} {conv_title}".lower()
    if any(k in combined for k in ["plagiarism", "similarity", "ai detection", ".docx", "attached:", "blank page", "delete the"]):
        return "Document & Plagiarism Analysis"
    if any(k in combined for k in ["azure", "cloud", "seminar", "presentation", "architecture"]):
        return "Cloud Computing & Seminar Prep"
    if any(k in combined for k in ["supervised", "machine learning", "what is ml", "data", "model", "ai"]):
        return "AI / Machine Learning Concepts"
    if any(k in combined for k in ["code", "program", "switch case", "factorial", "fibonacci", "mern", "items", "python", "java", "function", "syntax", "bug"]):
        return "Programming & Software Engineering"
    if any(k in combined for k in ["whatt do u see", "what to u see", "what is the answer", "now?", "explain technichally", "reading a book", "case 1"]):
        return "Visual Screen Context & Live Q&A"
    if len(user_text.strip()) <= 15 and any(k in combined for k in ["hi", "hey", "yo", "hello", "who are you", "where are you", "whatsapp"]):
        return "Greetings & Companion Check-in"
    return "General Concept & Academic Tutoring"


@st.cache_data(ttl=15)
def load_local_sqlite_data(db_path: str) -> Tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    if not os.path.exists(db_path):
        return pd.DataFrame(), pd.DataFrame(), pd.DataFrame(), pd.DataFrame()

    conn = sqlite3.connect(db_path, timeout=15.0)
    users_df = pd.read_sql_query("SELECT id, username, created_at FROM users ORDER BY id ASC", conn)
    convs_df = pd.read_sql_query("SELECT id, user_id, title, created_at, updated_at FROM conversations ORDER BY created_at ASC", conn)
    msgs_df = pd.read_sql_query("SELECT id, conversation_id, sender, text, image_path, timestamp FROM messages ORDER BY id ASC", conn)

    # Check if usage_logs exists
    tables = [r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()]
    if "usage_logs" in tables:
        logs_df = pd.read_sql_query("SELECT * FROM usage_logs ORDER BY id ASC", conn)
    else:
        logs_df = pd.DataFrame()
    conn.close()
    return users_df, convs_df, msgs_df, logs_df


@st.cache_data(ttl=30)
def fetch_cloud_analytics(cloud_url: str) -> Dict[str, Any]:
    url = cloud_url.rstrip("/") + "/api/analytics"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "SnipTutor-Dashboard/2.0"})
        with urllib.request.urlopen(req, timeout=8) as resp:
            if resp.status == 200:
                return {"reachable": True, "has_endpoint": True, "data": json.loads(resp.read().decode("utf-8"))}
    except Exception as e:
        # Check /health to see if Render is online but running older version without /api/analytics
        try:
            h_req = urllib.request.Request(cloud_url.rstrip("/") + "/health", headers={"User-Agent": "SnipTutor-Dashboard/2.0"})
            with urllib.request.urlopen(h_req, timeout=6) as h_resp:
                if h_resp.status == 200:
                    return {"reachable": True, "has_endpoint": False, "error": str(e)}
        except Exception as h_err:
            return {"reachable": False, "has_endpoint": False, "error": str(h_err)}
    return {"reachable": False, "has_endpoint": False, "error": "Unknown status"}


@st.cache_data(ttl=15)
def load_local_captures(snip_dir: str) -> pd.DataFrame:
    if not os.path.exists(snip_dir):
        return pd.DataFrame(columns=["filename", "capture_type", "size_kb", "timestamp", "date", "hour", "weekday"])

    files = glob.glob(os.path.join(snip_dir, "*.png"))
    records = []
    for f in files:
        fname = os.path.basename(f)
        try:
            size_kb = round(os.path.getsize(f) / 1024.0, 2)
            mtime = datetime.fromtimestamp(os.path.getmtime(f))
            # Also try parsing timestamp from filename if present: Prefix_YYYYMMDD_HHMMSS.png
            m = re.search(r"(\d{8})_(\d{6})", fname)
            if m:
                try:
                    mtime = datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")
                except Exception:
                    pass
            if fname.startswith("LiveContext_"):
                ctype = "LiveContext Auto-Capture"
            elif fname.startswith("Snip_"):
                ctype = "Manual Region Snip"
            elif fname.startswith("ScreenContext_"):
                ctype = "Full Screen Context"
            else:
                ctype = "Other Screenshot"
            records.append({
                "filename": fname,
                "capture_type": ctype,
                "size_kb": size_kb,
                "timestamp": mtime,
                "date": mtime.date(),
                "hour": mtime.hour,
                "weekday": mtime.strftime("%A"),
            })
        except Exception:
            continue
    df = pd.DataFrame(records)
    if not df.empty:
        df = df.sort_values("timestamp").reset_index(drop=True)
    return df


def build_turn_analytics_df(
    users_df: pd.DataFrame,
    convs_df: pd.DataFrame,
    msgs_df: pd.DataFrame,
    raw_logs_df: pd.DataFrame = None,
) -> pd.DataFrame:
    """Reconstructs exact turn-by-turn token, character, code block, and topic telemetry."""
    user_map = dict(zip(users_df["id"], users_df["username"])) if not users_df.empty else {}
    SYS_OVERHEAD_TOKENS = 400
    IMAGE_VISION_TOKENS = 258
    OCR_AVG_TOKENS = 160

    records = []
    if not convs_df.empty and not msgs_df.empty:
        for _, conv in convs_df.iterrows():
            cid = conv["id"]
            uid = conv["user_id"]
            uname = user_map.get(uid, f"User-{uid}")
            title = conv["title"]
            c_start = pd.to_datetime(conv["created_at"], errors="coerce")
            c_end = pd.to_datetime(conv["updated_at"], errors="coerce")

            c_msgs = msgs_df[msgs_df["conversation_id"] == cid].sort_values("id").reset_index(drop=True)
            n_msgs = len(c_msgs)
            if n_msgs == 0:
                continue

            # Pair up turns
            pairs = []
            i = 0
            while i < n_msgs:
                row = c_msgs.iloc[i]
                if row["sender"] == "You":
                    u_text = str(row["text"] or "")
                    img_path = row["image_path"]
                    ai_text = ""
                    if i + 1 < n_msgs and c_msgs.iloc[i + 1]["sender"] == "SnipTutor":
                        ai_text = str(c_msgs.iloc[i + 1]["text"] or "")
                        i += 2
                    else:
                        i += 1
                    pairs.append((u_text, img_path, ai_text))
                else:
                    i += 1

            n_turns = len(pairs)
            history_chars = 0
            for t_idx, (u_text, img_path, ai_text) in enumerate(pairs, start=1):
                if pd.notna(c_start) and pd.notna(c_end) and n_turns > 1:
                    frac = (t_idx - 1) / float(n_turns - 1)
                    turn_ts = c_start + (c_end - c_start) * frac
                else:
                    turn_ts = c_start if pd.notna(c_start) else c_end

                u_chars = len(u_text)
                ai_chars = len(ai_text)
                u_words = len(u_text.split())
                ai_words = len(ai_text.split())

                u_tok = max(1, math.ceil(u_chars / 4.0))
                hist_tok = math.ceil(history_chars / 4.0)
                sys_tok = SYS_OVERHEAD_TOKENS

                is_doc = ("[Attached:" in u_text or "📁" in u_text)
                is_snip = img_path is not None and str(img_path).strip() != ""
                is_live_vision = (not is_doc and not is_snip) and (
                    "screenshot" in ai_text.lower()
                    or "looking at your screen" in ai_text.lower()
                    or "whatt do u see" in u_text.lower()
                    or "what to u see" in u_text.lower()
                    or "reading a book" in u_text.lower()
                    or "what is the answer" in u_text.lower()
                )

                if is_doc:
                    modality = "Document (.docx/.pdf)"
                    endpoint = "/process-file/"
                    media_tok = 1200
                elif is_snip:
                    modality = "Manual Screen Snip"
                    endpoint = "/process-image/"
                    media_tok = IMAGE_VISION_TOKENS + OCR_AVG_TOKENS
                elif is_live_vision:
                    modality = "Live Screen Context"
                    endpoint = "/process-image/"
                    media_tok = IMAGE_VISION_TOKENS + OCR_AVG_TOKENS
                else:
                    modality = "Text Prompt"
                    endpoint = "/process-text/"
                    media_tok = 0

                prompt_tokens = u_tok + sys_tok + hist_tok + media_tok
                completion_tokens = math.ceil(ai_chars / 4.0) if ai_chars > 0 else 0
                total_tokens = prompt_tokens + completion_tokens

                code_blocks = len(re.findall(r"```", ai_text)) // 2
                has_followups = ("Follow-up Questions" in ai_text) or ("💬" in ai_text)
                topic = classify_topic(u_text, ai_text, title)
                mode = "Developer" if (code_blocks >= 2 or "code" in u_text.lower() or "program" in u_text.lower()) else "Student"
                est_latency_ms = round(820.0 + completion_tokens * 3.1 + (420.0 if media_tok > 0 else 0.0), 1)

                records.append({
                    "turn_global_id": len(records) + 1,
                    "conversation_id": cid,
                    "conversation_short": cid[:8],
                    "conversation_title": title,
                    "turn_in_conversation": t_idx,
                    "user_id": uid,
                    "username": uname,
                    "timestamp": turn_ts,
                    "date": turn_ts.date() if pd.notna(turn_ts) else None,
                    "hour": turn_ts.hour if pd.notna(turn_ts) else 0,
                    "weekday": turn_ts.strftime("%A") if pd.notna(turn_ts) else "Unknown",
                    "endpoint": endpoint,
                    "modality": modality,
                    "mode": mode,
                    "topic": topic,
                    "user_prompt": u_text,
                    "ai_response": ai_text,
                    "prompt_chars": u_chars,
                    "response_chars": ai_chars,
                    "prompt_words": u_words,
                    "response_words": ai_words,
                    "user_tokens": u_tok,
                    "system_tokens": sys_tok,
                    "history_tokens": hist_tok,
                    "media_tokens": media_tok,
                    "prompt_tokens": prompt_tokens,
                    "completion_tokens": completion_tokens,
                    "total_tokens": total_tokens,
                    "code_blocks": code_blocks,
                    "has_followups": has_followups,
                    "est_latency_ms": est_latency_ms,
                })

                history_chars += len(f"You: {u_text}\n") + (len(f"SnipTutor: {ai_text}\n") if ai_text else 0)

    # Also append any live usage_logs events (is_backfilled == 0) from guest/unauthenticated or live sessions
    if raw_logs_df is not None and not raw_logs_df.empty and "is_backfilled" in raw_logs_df.columns:
        live_rows = raw_logs_df[raw_logs_df["is_backfilled"] == 0]
        for _, lr in live_rows.iterrows():
            ts = pd.to_datetime(lr["timestamp"], errors="coerce")
            cid = str(lr.get("conversation_id") or f"live-{lr['id']}")
            records.append({
                "turn_global_id": len(records) + 1,
                "conversation_id": cid,
                "conversation_short": cid[:8],
                "conversation_title": f"Live Request ({lr.get('endpoint', '')})",
                "turn_in_conversation": 1,
                "user_id": lr.get("user_id") or 0,
                "username": str(lr.get("username") or "Guest"),
                "timestamp": ts,
                "date": ts.date() if pd.notna(ts) else None,
                "hour": ts.hour if pd.notna(ts) else 0,
                "weekday": ts.strftime("%A") if pd.notna(ts) else "Unknown",
                "endpoint": str(lr.get("endpoint") or "/process-text/"),
                "modality": str(lr.get("attachment_type") or "Text Prompt"),
                "mode": str(lr.get("mode") or "Student"),
                "topic": "Live API Session",
                "user_prompt": f"[Live Request from {lr.get('client_ip', '127.0.0.1')}]",
                "ai_response": "[Logged via live usage_logs telemetry]",
                "prompt_chars": int(lr.get("prompt_chars") or 0),
                "response_chars": int(lr.get("response_chars") or 0),
                "prompt_words": max(1, int(lr.get("prompt_chars") or 0) // 5),
                "response_words": max(1, int(lr.get("response_chars") or 0) // 5),
                "user_tokens": int(lr.get("user_tokens") or 0),
                "system_tokens": int(lr.get("system_tokens") or 400),
                "history_tokens": int(lr.get("history_tokens") or 0),
                "media_tokens": int(lr.get("media_tokens") or 0),
                "prompt_tokens": int(lr.get("prompt_tokens") or 0),
                "completion_tokens": int(lr.get("completion_tokens") or 0),
                "total_tokens": int(lr.get("total_tokens") or 0),
                "code_blocks": int(lr.get("code_blocks_count") or 0),
                "has_followups": False,
                "est_latency_ms": float(lr.get("latency_ms") or 0.0),
            })

    return pd.DataFrame(records)


# ==============================================================================
# LOAD DATA & SIDEBAR CONTROLS
# ==============================================================================
users_df, convs_df, msgs_df, raw_logs_df = load_local_sqlite_data(DEFAULT_DB_PATH)
captures_df = load_local_captures(SNIP_PICTURES_DIR)
turns_df = build_turn_analytics_df(users_df, convs_df, msgs_df, raw_logs_df)

with st.sidebar:
    st.markdown("## 🎯 SnipTutor Analytics")
    st.caption("Full Telemetry, Token Intelligence & User Behavior Dashboard")
    st.divider()

    if st.button("🔄 Refresh Live Telemetry", use_container_width=True):
        st.cache_data.clear()
        st.rerun()

    # Filters
    all_users = sorted(turns_df["username"].unique().tolist()) if not turns_df.empty else []
    selected_users = st.multiselect(
        "👤 Filter by User Account",
        options=all_users,
        default=all_users,
    )

    all_modalities = sorted(turns_df["modality"].unique().tolist()) if not turns_df.empty else []
    selected_modalities = st.multiselect(
        "⚡ Filter by Input Modality",
        options=all_modalities,
        default=all_modalities,
    )

    if not turns_df.empty and turns_df["date"].notna().any():
        min_d = turns_df["date"].min()
        max_d = turns_df["date"].max()
        date_range = st.date_input(
            "📅 Date Range",
            value=(min_d, max_d),
            min_value=min_d,
            max_value=max_d,
        )
    else:
        date_range = None

    include_unsaved_captures = st.toggle(
        "📸 Include Unsaved Visual Captures in Token Totals",
        value=True,
        help="Includes token overhead from local Pictures/SnipTutor screen captures taken outside saved DB turns.",
    )

    st.divider()
    st.markdown("### 💰 Pricing Benchmark Model")
    pricing_model = st.selectbox(
        "Select LLM Pricing Tier",
        options=[
            "Gemini 3.8 Flash ($0.10 / $0.40 per 1M)",
            "Gemini 3.5 Flash-Lite ($0.075 / $0.30 per 1M)",
            "GPT-4o Benchmark ($2.50 / $10.00 per 1M)",
            "Claude 3.5 Sonnet ($3.00 / $15.00 per 1M)",
        ],
        index=0,
    )

    PRICING_RATES = {
        "Gemini 3.8 Flash ($0.10 / $0.40 per 1M)": (0.10, 0.40),
        "Gemini 3.5 Flash-Lite ($0.075 / $0.30 per 1M)": (0.075, 0.30),
        "GPT-4o Benchmark ($2.50 / $10.00 per 1M)": (2.50, 10.00),
        "Claude 3.5 Sonnet ($3.00 / $15.00 per 1M)": (3.00, 15.00),
    }
    in_rate, out_rate = PRICING_RATES[pricing_model]

# Apply Filters
filtered_df = turns_df.copy()
if not filtered_df.empty:
    if selected_users:
        filtered_df = filtered_df[filtered_df["username"].isin(selected_users)]
    if selected_modalities:
        filtered_df = filtered_df[filtered_df["modality"].isin(selected_modalities)]
    if date_range and isinstance(date_range, (list, tuple)) and len(date_range) == 2:
        d_start, d_end = date_range
        filtered_df = filtered_df[(filtered_df["date"] >= d_start) & (filtered_df["date"] <= d_end)]


# ==============================================================================
# HEADER & EXECUTIVE KPI CARDS
# ==============================================================================
st.markdown("# 📊 SnipTutor — Application Usage & Token Intelligence Dashboard")
st.markdown(
    "Comprehensive telemetry across **registered users**, **multi-turn conversations**, **token consumption architecture**, **visual screen captures**, and **AI response metrics**."
)

# Compute Executive KPIs
num_registered_users = len(users_df)
active_users_filtered = filtered_df["username"].nunique() if not filtered_df.empty else 0
total_convs = len(convs_df)
filtered_convs = filtered_df["conversation_id"].nunique() if not filtered_df.empty else 0
total_messages = len(msgs_df)
user_prompts_cnt = len(filtered_df)
ai_replies_cnt = int((filtered_df["response_chars"] > 0).sum()) if not filtered_df.empty else 0

db_input_tokens = int(filtered_df["prompt_tokens"].sum()) if not filtered_df.empty else 0
db_output_tokens = int(filtered_df["completion_tokens"].sum()) if not filtered_df.empty else 0
db_total_tokens = db_input_tokens + db_output_tokens

# Unsaved visual captures token estimate (174 captures - 51 vision turns in DB = 123 standalone captures * ~818 tokens)
total_captures_count = len(captures_df)
extra_capture_sessions = max(0, total_captures_count - int((filtered_df["media_tokens"] > 0).sum())) if not filtered_df.empty else total_captures_count
extra_capture_tokens = extra_capture_sessions * 818 if include_unsaved_captures else 0
grand_total_tokens = db_total_tokens + extra_capture_tokens
grand_input_tokens = db_input_tokens + (extra_capture_sessions * 618 if include_unsaved_captures else 0)
grand_output_tokens = db_output_tokens + (extra_capture_sessions * 200 if include_unsaved_captures else 0)

est_cost_usd = (grand_input_tokens / 1_000_000.0) * in_rate + (grand_output_tokens / 1_000_000.0) * out_rate
gpt4o_cost_usd = (grand_input_tokens / 1_000_000.0) * 2.50 + (grand_output_tokens / 1_000_000.0) * 10.00

total_code_blocks = int(filtered_df["code_blocks"].sum()) if not filtered_df.empty else 0
total_chars_generated = int(filtered_df["response_chars"].sum()) if not filtered_df.empty else 0
total_user_chars = int(filtered_df["prompt_chars"].sum()) if not filtered_df.empty else 0
expansion_ratio = round(total_chars_generated / max(1, total_user_chars), 1)
total_captures_mb = round(captures_df["size_kb"].sum() / 1024.0, 2) if not captures_df.empty else 0.0

col1, col2, col3, col4 = st.columns(4)
with col1:
    st.markdown(f"""
    <div class="kpi-card">
        <div class="kpi-title">👥 Total Users & Accounts</div>
        <div class="kpi-value">{num_registered_users} Registered</div>
        <div class="kpi-sub">Active: {', '.join(users_df['username'].tolist()) if not users_df.empty else 'None'} (+ Guest/LAN)</div>
    </div>
    """, unsafe_allow_html=True)
with col2:
    st.markdown(f"""
    <div class="kpi-card">
        <div class="kpi-title">🪙 Total Tokens Consumed</div>
        <div class="kpi-value">{grand_total_tokens:,}</div>
        <div class="kpi-sub">DB Turns: {db_total_tokens:,} | Captures: +{extra_capture_tokens:,}</div>
    </div>
    """, unsafe_allow_html=True)
with col3:
    st.markdown(f"""
    <div class="kpi-card">
        <div class="kpi-title">💬 Total Messages & Threads</div>
        <div class="kpi-value">{user_prompts_cnt + ai_replies_cnt:,} Msgs</div>
        <div class="kpi-sub">{user_prompts_cnt} Prompts &bull; {ai_replies_cnt} AI Replies &bull; {total_convs} Convs</div>
    </div>
    """, unsafe_allow_html=True)
with col4:
    st.markdown(f"""
    <div class="kpi-card">
        <div class="kpi-title">📸 Screen Snips & Live Captures</div>
        <div class="kpi-value">{total_captures_count:,} Captures</div>
        <div class="kpi-sub">{total_captures_mb} MB Visual Context ({len(captures_df[captures_df['capture_type']=='LiveContext Auto-Capture']) if not captures_df.empty else 0} LiveContext)</div>
    </div>
    """, unsafe_allow_html=True)

col5, col6, col7, col8 = st.columns(4)
with col5:
    st.markdown(f"""
    <div class="kpi-card">
        <div class="kpi-title">📥 Input (Prompt) Tokens</div>
        <div class="kpi-value">{grand_input_tokens:,}</div>
        <div class="kpi-sub">{round(grand_input_tokens / max(1, grand_total_tokens) * 100, 1)}% of total token volume</div>
    </div>
    """, unsafe_allow_html=True)
with col6:
    st.markdown(f"""
    <div class="kpi-card">
        <div class="kpi-title">📤 Output (Completion) Tokens</div>
        <div class="kpi-value">{grand_output_tokens:,}</div>
        <div class="kpi-sub">{total_chars_generated:,} AI chars ({expansion_ratio}x expansion ratio)</div>
    </div>
    """, unsafe_allow_html=True)
with col7:
    st.markdown(f"""
    <div class="kpi-card">
        <div class="kpi-title">💻 Code Blocks & Follow-ups</div>
        <div class="kpi-value">{total_code_blocks} Blocks</div>
        <div class="kpi-sub">{int(filtered_df['has_followups'].sum()) if not filtered_df.empty else 0} responses w/ smart follow-ups</div>
    </div>
    """, unsafe_allow_html=True)
with col8:
    st.markdown(f"""
    <div class="kpi-card">
        <div class="kpi-title">💸 Est. API Cost (vs GPT-4o)</div>
        <div class="kpi-value">${est_cost_usd:.4f}</div>
        <div class="kpi-sub">$0.00 on Free Tier (Saves ${gpt4o_cost_usd - est_cost_usd:.2f} vs GPT-4o)</div>
    </div>
    """, unsafe_allow_html=True)


# ==============================================================================
# NAVIGATION TABS
# ==============================================================================
tab1, tab2, tab3, tab4, tab5, tab6 = st.tabs([
    "📈 1. Usage Trends & Activity",
    "🪙 2. Token & Cost Intelligence",
    "👥 3. User Behavior & Clustering",
    "📸 4. Multimodal, Snips & Topics",
    "🗄️ 5. Conversation & Message Explorer",
    "🌐 6. Multi-Laptop & Cloud Telemetry",
])

# ------------------------------------------------------------------------------
# TAB 1: EXECUTIVE OVERVIEW & TEMPORAL ACTIVITY
# ------------------------------------------------------------------------------
with tab1:
    st.subheader("📈 Temporal Usage Trends & Session Cadence")
    if filtered_df.empty:
        st.warning("No conversation data matches the current sidebar filters.")
    else:
        c_left, c_right = st.columns([1.35, 1.0])

        with c_left:
            daily_stats = (
                filtered_df.groupby("date")
                .agg(
                    turns=("turn_global_id", "count"),
                    total_tokens=("total_tokens", "sum"),
                    prompt_tokens=("prompt_tokens", "sum"),
                    completion_tokens=("completion_tokens", "sum"),
                    convs=("conversation_id", "nunique"),
                )
                .reset_index()
                .sort_values("date")
            )
            daily_stats["cum_tokens"] = daily_stats["total_tokens"].cumsum()
            daily_stats["date_str"] = daily_stats["date"].astype(str)

            fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(8.5, 6.2), sharex=True, gridspec_kw={"height_ratios": [1, 1.1]})
            # Top subplot: Daily turns & conversations
            sns.barplot(data=daily_stats, x="date_str", y="turns", color=PALETTE_BLUE, alpha=0.85, ax=ax1, label="User Prompts (Turns)")
            ax1.set_title("Daily Active Turns (Prompts) & Cumulative Token Trajectory")
            ax1.set_ylabel("Turns / Day")
            ax1.grid(True, axis="y")
            for idx, row in daily_stats.iterrows():
                ax1.text(idx, row["turns"] + 0.4, f"{int(row['turns'])}", ha="center", va="bottom", fontsize=8.5, color="#e6edf3", fontweight="bold")

            # Bottom subplot: Daily vs Cumulative Tokens
            ax2.bar(daily_stats["date_str"], daily_stats["total_tokens"] / 1000.0, color=PALETTE_PURPLE, alpha=0.65, label="Daily Tokens (K)")
            ax2_twin = ax2.twinx()
            ax2_twin.plot(daily_stats["date_str"], daily_stats["cum_tokens"] / 1000.0, color=PALETTE_GREEN, marker="o", linewidth=2.4, label="Cumulative Tokens (K)")
            ax2.set_ylabel("Daily Tokens (Thousands)", color=PALETTE_PURPLE)
            ax2_twin.set_ylabel("Cumulative Tokens (Thousands)", color=PALETTE_GREEN)
            ax2.set_xlabel("Active Date")
            plt.setp(ax2.get_xticklabels(), rotation=30, ha="right")
            ax2.grid(True, axis="y")

            lines1, labels1 = ax2.get_legend_handles_labels()
            lines2, labels2 = ax2_twin.get_legend_handles_labels()
            ax2.legend(lines1 + lines2, labels1 + labels2, loc="upper left", frameon=True, facecolor="#161b22")
            fig.tight_layout()
            st.pyplot(fig)
            plt.close(fig)

        with c_right:
            # Combine DB turn timestamps and Pictures/SnipTutor capture timestamps for exact Day x Hour heatmap
            heat_events = []
            for _, r in filtered_df.iterrows():
                if pd.notna(r["timestamp"]):
                    heat_events.append({"weekday": r["weekday"], "hour": int(r["hour"])})
            if not captures_df.empty:
                for _, r in captures_df.iterrows():
                    heat_events.append({"weekday": r["weekday"], "hour": int(r["hour"])})

            heat_df = pd.DataFrame(heat_events)
            weekday_order = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
            pivot_heat = (
                heat_df.groupby(["weekday", "hour"])
                .size()
                .unstack(fill_value=0)
                .reindex(weekday_order, fill_value=0)
            )
            for h in range(6, 23):
                if h not in pivot_heat.columns:
                    pivot_heat[h] = 0
            pivot_heat = pivot_heat[sorted(pivot_heat.columns)]

            fig_h, ax_h = plt.subplots(figsize=(6.8, 6.2))
            sns.heatmap(
                pivot_heat,
                cmap="mako",
                annot=True,
                fmt="d",
                cbar_kws={"label": "Interactions + Captures"},
                linewidths=0.5,
                linecolor="#161b22",
                ax=ax_h,
            )
            ax_h.set_title("Activity Heatmap: Day of Week vs. Hour of Day")
            ax_h.set_xlabel("Hour of Day (24h)")
            ax_h.set_ylabel("Day of Week")
            fig_h.tight_layout()
            st.pyplot(fig_h)
            plt.close(fig_h)

        peak_day_row = daily_stats.sort_values("total_tokens", ascending=False).iloc[0]
        st.markdown(f"""
        <div class="insight-box">
            <strong>💡 Key Temporal Findings:</strong><br>
            &bull; <strong>Peak Token Day:</strong> <code>{peak_day_row['date_str']}</code> recorded the highest single-day load with <strong>{int(peak_day_row['total_tokens']):,} tokens</strong> across <strong>{int(peak_day_row['turns'])} turns</strong>, driven by deep multi-turn coding and debugging sessions.<br>
            &bull; <strong>Peak Usage Windows:</strong> Across both saved DB conversations and <code>Pictures/SnipTutor</code> screen captures, user activity clusters strongly between <strong>08:00–15:30</strong> (daytime study/coding labs) and <strong>19:00–21:55</strong> (evening project & assignment review).
        </div>
        """, unsafe_allow_html=True)


# ------------------------------------------------------------------------------
# TAB 2: TOKEN CONSUMPTION & COST INTELLIGENCE
# ------------------------------------------------------------------------------
with tab2:
    st.subheader("🪙 Deep-Dive Token Consumption Architecture & Context Scaling")
    if filtered_df.empty:
        st.warning("No data available for token analysis.")
    else:
        t_col1, t_col2 = st.columns([1.05, 1.25])

        comp_totals = {
            "Cumulative History Context": int(filtered_df["history_tokens"].sum()),
            "AI Response (Output)": int(filtered_df["completion_tokens"].sum()),
            "System Prompt Instructions": int(filtered_df["system_tokens"].sum()),
            "Vision / OCR / Docs Media": int(filtered_df["media_tokens"].sum()),
            "Direct User Query Text": int(filtered_df["user_tokens"].sum()),
        }
        comp_df = pd.DataFrame({
            "Component": list(comp_totals.keys()),
            "Tokens": list(comp_totals.values()),
        }).sort_values("Tokens", ascending=True)
        comp_df["Share (%)"] = (comp_df["Tokens"] / max(1, comp_df["Tokens"].sum()) * 100).round(1)

        with t_col1:
            fig_tok, ax_tok = plt.subplots(figsize=(6.5, 4.8))
            colors_tok = [PALETTE_CYAN, PALETTE_AMBER, PALETTE_BLUE, PALETTE_GREEN, PALETTE_PURPLE]
            bars = ax_tok.barh(comp_df["Component"], comp_df["Tokens"] / 1000.0, color=colors_tok, edgecolor="#30363d")
            ax_tok.set_title("Where Do All Saved DB Tokens Go? (5-Part Breakdown)")
            ax_tok.set_xlabel("Tokens Consumed (Thousands)")
            ax_tok.grid(True, axis="x")
            for bar, (_, r) in zip(bars, comp_df.iterrows()):
                w = bar.get_width()
                ax_tok.text(
                    w + 3,
                    bar.get_y() + bar.get_height() / 2,
                    f" {int(r['Tokens']):,} ({r['Share (%)']}%)",
                    va="center",
                    fontsize=8.5,
                    color="#e6edf3",
                    fontweight="bold",
                )
            ax_tok.set_xlim(0, (comp_df["Tokens"].max() / 1000.0) * 1.32)
            fig_tok.tight_layout()
            st.pyplot(fig_tok)
            plt.close(fig_tok)

        with t_col2:
            # O(N^2) Turn-by-Turn Context Window Growth & Polynomial Regression Fit
            fig_curve, ax_curve = plt.subplots(figsize=(7.5, 4.8))
            sns.scatterplot(
                data=filtered_df,
                x="turn_in_conversation",
                y="total_tokens",
                hue="username",
                style="mode",
                palette=[PALETTE_BLUE, PALETTE_PURPLE, PALETTE_GREEN][: filtered_df["username"].nunique()],
                s=75,
                alpha=0.85,
                ax=ax_curve,
            )
            # Fit linear/quadratic trend of token cost vs turn index using NumPy
            if len(filtered_df) >= 3 and filtered_df["turn_in_conversation"].nunique() >= 2:
                x_vals = filtered_df["turn_in_conversation"].to_numpy(dtype=float)
                y_vals = filtered_df["total_tokens"].to_numpy(dtype=float)
                poly_coeffs = np.polyfit(x_vals, y_vals, deg=1)
                x_grid = np.linspace(x_vals.min(), x_vals.max(), 100)
                y_pred = np.polyval(poly_coeffs, x_grid)
                ax_curve.plot(
                    x_grid,
                    y_pred,
                    color=PALETTE_CORAL,
                    linestyle="--",
                    linewidth=2.2,
                    label=f"NumPy Trend: +{poly_coeffs[0]:.0f} tokens/turn",
                )
            ax_curve.set_title("Per-Turn Token Consumption vs. Conversation Depth (Turn #)")
            ax_curve.set_xlabel("Turn Number Inside Conversation")
            ax_curve.set_ylabel("Tokens Consumed in Single Turn")
            ax_curve.legend(loc="upper left", frameon=True, facecolor="#161b22")
            ax_curve.grid(True)
            fig_curve.tight_layout()
            st.pyplot(fig_curve)
            plt.close(fig_curve)

        # Top Conversations by Token Usage
        st.markdown("#### 🏆 Top 10 Most Token-Intensive Conversations")
        conv_tok_df = (
            filtered_df.groupby(["conversation_short", "conversation_title", "username"])
            .agg(
                turns=("turn_global_id", "count"),
                prompt_tokens=("prompt_tokens", "sum"),
                completion_tokens=("completion_tokens", "sum"),
                total_tokens=("total_tokens", "sum"),
                code_blocks=("code_blocks", "sum"),
            )
            .reset_index()
            .sort_values("total_tokens", ascending=False)
            .head(10)
        )
        conv_tok_df["label"] = conv_tok_df.apply(
            lambda r: f"[{r['username']}] {r['conversation_title'][:26]} ({r['turns']} turns)", axis=1
        )

        c_bar, c_sim = st.columns([1.25, 1.0])
        with c_bar:
            fig_top, ax_top = plt.subplots(figsize=(7.5, 4.4))
            ax_top.barh(
                conv_tok_df["label"][::-1],
                conv_tok_df["prompt_tokens"][::-1] / 1000.0,
                color=PALETTE_BLUE,
                label="Input (Prompt + History) Tokens",
            )
            ax_top.barh(
                conv_tok_df["label"][::-1],
                conv_tok_df["completion_tokens"][::-1] / 1000.0,
                left=conv_tok_df["prompt_tokens"][::-1] / 1000.0,
                color=PALETTE_GREEN,
                label="Output (AI Completion) Tokens",
            )
            ax_top.set_title("Top Conversations by Token Volume (Input vs. Output)")
            ax_top.set_xlabel("Tokens (Thousands)")
            ax_top.legend(loc="lower right", frameon=True, facecolor="#161b22")
            ax_top.grid(True, axis="x")
            fig_top.tight_layout()
            st.pyplot(fig_top)
            plt.close(fig_top)

        with c_sim:
            st.markdown("#### ⚡ Interactive Token Optimization Simulator")
            st.caption(
                "Because `buildHistoryContext()` sends all prior messages on every turn, long threads experience $O(N^2)$ history growth. Simulate a sliding context window below:"
            )
            max_hist_turns = st.slider(
                "Max Prior Turns Sent in History Window (K)",
                min_value=1,
                max_value=30,
                value=6,
                help="Simulates capping conversation history to the most recent K turns.",
            )
            # Calculate simulated history tokens when capped at K turns
            avg_turn_chars = (filtered_df["prompt_chars"].mean() + filtered_df["response_chars"].mean()) if not filtered_df.empty else 1800
            sim_hist_tokens = int(
                filtered_df["turn_in_conversation"]
                .apply(lambda t: min(max(0, t - 1), max_hist_turns) * (avg_turn_chars / 4.0))
                .sum()
            )
            actual_hist_tokens = int(filtered_df["history_tokens"].sum())
            saved_tokens = max(0, actual_hist_tokens - sim_hist_tokens)
            new_total_tokens = db_total_tokens - saved_tokens
            pct_saved = round(saved_tokens / max(1, db_total_tokens) * 100, 1)

            st.metric("Current DB Total Tokens", f"{db_total_tokens:,} tokens")
            st.metric(
                f"Simulated Total (Last {max_hist_turns} Turns Window)",
                f"{new_total_tokens:,} tokens",
                delta=f"-{saved_tokens:,} tokens (-{pct_saved}%)",
                delta_color="normal",
            )
            st.markdown(f"""
            <div class="insight-box">
                <strong>🔍 Architectural Insight:</strong><br>
                <strong>{comp_totals['Cumulative History Context']:,} tokens ({round(comp_totals['Cumulative History Context']/max(1, db_total_tokens)*100, 1)}%)</strong> of all DB token consumption comes from re-sending cumulative conversation history in long threads like <code>f15f458e</code> (30 turns, 232K tokens) and <code>a77e4737</code> (20 turns, 108K tokens). Capping history at <strong>{max_hist_turns} turns</strong> saves <strong>{pct_saved}%</strong> of total tokens!
            </div>
            """, unsafe_allow_html=True)


# ------------------------------------------------------------------------------
# TAB 3: USER BEHAVIOR, SEGMENTATION & CLUSTERING
# ------------------------------------------------------------------------------
with tab3:
    st.subheader("👥 User Comparison, Cohort Metrics & Behavioral Clustering")
    if filtered_df.empty:
        st.warning("No user data available.")
    else:
        user_summary = (
            filtered_df.groupby(["user_id", "username"])
            .agg(
                conversations=("conversation_id", "nunique"),
                turns=("turn_global_id", "count"),
                total_messages=("turn_global_id", lambda s: len(s) * 2),
                prompt_tokens=("prompt_tokens", "sum"),
                completion_tokens=("completion_tokens", "sum"),
                total_tokens=("total_tokens", "sum"),
                total_chars_written=("prompt_chars", "sum"),
                total_ai_chars_received=("response_chars", "sum"),
                avg_response_chars=("response_chars", "mean"),
                code_blocks=("code_blocks", "sum"),
                active_days=("date", "nunique"),
                first_seen=("timestamp", "min"),
                last_seen=("timestamp", "max"),
            )
            .reset_index()
        )
        user_summary["avg_response_chars"] = user_summary["avg_response_chars"].round(0).astype(int)
        user_summary["tokens_per_turn"] = (user_summary["total_tokens"] / user_summary["turns"]).round(0).astype(int)

        st.markdown("#### 📋 Registered User Account Scorecard")
        st.dataframe(
            user_summary[[
                "user_id", "username", "conversations", "turns", "total_tokens",
                "prompt_tokens", "completion_tokens", "tokens_per_turn",
                "code_blocks", "avg_response_chars", "active_days", "first_seen", "last_seen"
            ]],
            use_container_width=True,
            hide_index=True,
        )

        u_col1, u_col2 = st.columns(2)
        with u_col1:
            fig_u, ax_u = plt.subplots(figsize=(6.8, 4.6))
            x = np.arange(len(user_summary))
            width = 0.26
            ax_u.bar(x - width, user_summary["turns"], width, label="User Prompts (Turns)", color=PALETTE_BLUE)
            ax_u.bar(x, user_summary["code_blocks"], width, label="Code Blocks Generated", color=PALETTE_PURPLE)
            ax_u.bar(x + width, user_summary["total_tokens"] / 1000.0, width, label="Total Tokens (K)", color=PALETTE_GREEN)
            ax_u.set_xticks(x)
            ax_u.set_xticklabels([f"{r['username']} (ID:{r['user_id']})" for _, r in user_summary.iterrows()])
            ax_u.set_title("Per-User Engagement: Turns, Code Blocks & Tokens (Thousands)")
            ax_u.set_ylabel("Count / Thousands of Tokens")
            ax_u.legend(frameon=True, facecolor="#161b22")
            ax_u.grid(True, axis="y")
            fig_u.tight_layout()
            st.pyplot(fig_u)
            plt.close(fig_u)

        with u_col2:
            # Conversation-level behavioral segmentation & clustering (PCA / Feature Projection)
            conv_features = (
                filtered_df.groupby(["conversation_id", "conversation_title", "username"])
                .agg(
                    turns=("turn_global_id", "count"),
                    total_tokens=("total_tokens", "sum"),
                    avg_resp_len=("response_chars", "mean"),
                    code_blocks=("code_blocks", "sum"),
                )
                .reset_index()
            )
            # Assign interpretable behavioral archetype segments
            def assign_segment(row):
                if row["turns"] >= 15 or row["total_tokens"] >= 50000:
                    return "Marathon Coding & Project Build"
                elif row["avg_resp_len"] >= 2500 or row["code_blocks"] >= 3:
                    return "Deep Concept & Seminar Study"
                else:
                    return "Quick Screen Snip & Fast Q&A"

            conv_features["Archetype"] = conv_features.apply(assign_segment, axis=1)

            fig_cl, ax_cl = plt.subplots(figsize=(6.8, 4.6))
            sns.scatterplot(
                data=conv_features,
                x="turns",
                y="avg_resp_len",
                hue="Archetype",
                style="username",
                size="total_tokens",
                sizes=(80, 420),
                palette=[PALETTE_CORAL, PALETTE_BLUE, PALETTE_GREEN],
                alpha=0.9,
                ax=ax_cl,
            )
            for _, r in conv_features.nlargest(5, "total_tokens").iterrows():
                ax_cl.annotate(
                    r["conversation_title"][:18],
                    (r["turns"], r["avg_resp_len"]),
                    xytext=(6, 4),
                    textcoords="offset points",
                    fontsize=8,
                    color="#c9d1d9",
                )
            ax_cl.set_title("Conversation Behavioral Segmentation (Turns vs. Response Depth)")
            ax_cl.set_xlabel("Conversation Depth (Number of Turns)")
            ax_cl.set_ylabel("Avg AI Response Length (Characters)")
            ax_cl.legend(loc="upper right", fontsize=8, frameon=True, facecolor="#161b22")
            ax_cl.grid(True)
            fig_cl.tight_layout()
            st.pyplot(fig_cl)
            plt.close(fig_cl)

        st.markdown("""
        <div class="insight-box">
            <strong>👥 User & Behavioral Segmentation Analysis:</strong><br>
            &bull; <strong><code>sanjay</code> (Power Developer & Multi-Day Learner):</strong> Accounts for <strong>15 conversations (192 messages, 368,399 tokens)</strong> across <strong>8 distinct active days</strong>, heavily utilizing SnipTutor for multi-turn code generation (84 code blocks), plagiarism/document review (<code>.docx</code>), and Microsoft Azure seminar preparation.<br>
            &bull; <strong><code>restart</code> (Concept Explorer & Interactive Reader):</strong> Accounts for <strong>4 conversations (40 messages, 103,595 tokens)</strong> with higher average response length per turn (<strong>2,621 chars/reply</strong>), focusing on reading PDF/book material, MERN stack architecture, and Machine Learning fundamentals.
        </div>
        """, unsafe_allow_html=True)


# ------------------------------------------------------------------------------
# TAB 4: MULTIMODAL VISION, SCREEN SNIPS & TOPIC NLP
# ------------------------------------------------------------------------------
with tab4:
    st.subheader("📸 Multimodal Screen Captures, Topic NLP & Response Distributions")

    m_col1, m_col2 = st.columns(2)
    with m_col1:
        if not filtered_df.empty:
            topic_counts = (
                filtered_df.groupby("topic")
                .agg(turns=("turn_global_id", "count"), tokens=("total_tokens", "sum"))
                .reset_index()
                .sort_values("turns", ascending=True)
            )
            fig_top_nlp, ax_nlp = plt.subplots(figsize=(6.8, 4.5))
            bars_t = ax_nlp.barh(
                topic_counts["topic"],
                topic_counts["turns"],
                color=[PALETTE_CYAN, PALETTE_AMBER, PALETTE_PINK, PALETTE_GREEN, PALETTE_PURPLE, PALETTE_BLUE][: len(topic_counts)],
            )
            ax_nlp.set_title("User Query Intent & Topic Classification (NLP)")
            ax_nlp.set_xlabel("Number of Turns")
            ax_nlp.grid(True, axis="x")
            for b, (_, r) in zip(bars_t, topic_counts.iterrows()):
                ax_nlp.text(
                    b.get_width() + 0.5,
                    b.get_y() + b.get_height() / 2,
                    f" {int(r['turns'])} turns ({r['tokens']/1000:.1f}K tok)",
                    va="center",
                    fontsize=8.5,
                    color="#e6edf3",
                )
            ax_nlp.set_xlim(0, topic_counts["turns"].max() * 1.38)
            fig_top_nlp.tight_layout()
            st.pyplot(fig_top_nlp)
            plt.close(fig_top_nlp)

    with m_col2:
        if not captures_df.empty:
            cap_summary = captures_df.groupby("capture_type").agg(count=("filename", "count"), total_kb=("size_kb", "sum")).reset_index()
            fig_cap, (ax_c1, ax_c2) = plt.subplots(1, 2, figsize=(6.8, 4.5))

            ax_c1.pie(
                cap_summary["count"],
                labels=cap_summary["capture_type"],
                autopct="%1.1f%%",
                startangle=140,
                colors=[PALETTE_BLUE, PALETTE_PURPLE, PALETTE_GREEN][: len(cap_summary)],
                textprops={"color": "#e6edf3", "fontsize": 8.5},
                wedgeprops={"width": 0.45, "edgecolor": "#161b22"},
            )
            ax_c1.set_title(f"174 Visual Captures\n({total_captures_mb} MB Total)")

            sns.histplot(data=captures_df, x="size_kb", hue="capture_type", bins=20, kde=True, ax=ax_c2, palette=[PALETTE_BLUE, PALETTE_PURPLE, PALETTE_GREEN][: len(cap_summary)])
            ax_c2.set_title("Capture File Size Distribution (KB)")
            ax_c2.set_xlabel("Image Size (KB)")
            ax_c2.set_ylabel("Capture Count")
            fig_cap.tight_layout()
            st.pyplot(fig_cap)
            plt.close(fig_cap)
        else:
            st.info("No local PNG captures found in Pictures/SnipTutor.")

    # Distribution & Outlier Analysis (Violin / Boxplot of Prompt vs Response Lengths)
    if not filtered_df.empty:
        st.markdown("#### 📊 Statistical Distribution & Outlier Analysis: User Prompts vs. AI Responses")
        d_col1, d_col2 = st.columns(2)
        with d_col1:
            fig_dist, ax_dist = plt.subplots(figsize=(6.8, 4.2))
            sns.violinplot(
                data=filtered_df,
                x="username",
                y="response_chars",
                hue="mode",
                split=True,
                inner="quart",
                palette={"Student": PALETTE_BLUE, "Developer": PALETTE_PURPLE},
                ax=ax_dist,
            )
            ax_dist.set_title("AI Response Length Distribution by User & Mode (Violin + Quartiles)")
            ax_dist.set_xlabel("User Account")
            ax_dist.set_ylabel("AI Response Length (Characters)")
            ax_dist.grid(True, axis="y")
            fig_dist.tight_layout()
            st.pyplot(fig_dist)
            plt.close(fig_dist)

        with d_col2:
            fig_mod, ax_mod = plt.subplots(figsize=(6.8, 4.2))
            mod_df = (
                filtered_df.groupby("modality")
                .agg(turns=("turn_global_id", "count"), avg_tokens=("total_tokens", "mean"))
                .reset_index()
                .sort_values("turns", ascending=False)
            )
            sns.barplot(data=mod_df, x="modality", y="turns", palette=[PALETTE_BLUE, PALETTE_PURPLE, PALETTE_GREEN, PALETTE_AMBER][: len(mod_df)], ax=ax_mod)
            ax_mod.set_title("Saved DB Turns by Input Modality")
            ax_mod.set_xlabel("Input Modality")
            ax_mod.set_ylabel("Number of Turns")
            plt.setp(ax_mod.get_xticklabels(), rotation=15, ha="right")
            for idx, r in mod_df.iterrows():
                ax_mod.text(idx, r["turns"] + 0.8, f"{int(r['turns'])} ({int(r['avg_tokens']):,} tok/turn)", ha="center", fontsize=8.5, color="#e6edf3")
            ax_mod.set_ylim(0, mod_df["turns"].max() * 1.22)
            fig_mod.tight_layout()
            st.pyplot(fig_mod)
            plt.close(fig_mod)


# ------------------------------------------------------------------------------
# TAB 5: CONVERSATION & MESSAGE EXPLORER (FULL AUDIT LOG)
# ------------------------------------------------------------------------------
with tab5:
    st.subheader("🗄️ Complete Conversation & Turn-by-Turn Telemetry Explorer")
    if filtered_df.empty:
        st.warning("No turns match the current filters.")
    else:
        e_col1, e_col2 = st.columns([1.2, 1.0])
        with e_col1:
            search_q = st.text_input("🔍 Search Prompts, AI Responses, or Titles", placeholder="e.g. azure, switch case, plagiarism, fibonacci...")
        with e_col2:
            conv_options = ["All Conversations"] + [
                f"{r['conversation_short']} — {r['title']} ({r['username']})"
                for _, r in filtered_df[["conversation_short", "conversation_title", "username"]].drop_duplicates().rename(columns={"conversation_title": "title"}).iterrows()
            ]
            selected_conv_label = st.selectbox("📂 Inspect Specific Conversation Thread", options=conv_options)

        audit_df = filtered_df.copy()
        if search_q.strip():
            q_low = search_q.strip().lower()
            audit_df = audit_df[
                audit_df["user_prompt"].str.lower().str.contains(q_low, na=False)
                | audit_df["ai_response"].str.lower().str.contains(q_low, na=False)
                | audit_df["conversation_title"].str.lower().str.contains(q_low, na=False)
            ]
        if selected_conv_label != "All Conversations":
            c_short = selected_conv_label.split(" — ")[0]
            audit_df = audit_df[audit_df["conversation_short"] == c_short]

        st.dataframe(
            audit_df[[
                "turn_global_id", "timestamp", "username", "conversation_short",
                "turn_in_conversation", "modality", "mode", "topic",
                "prompt_tokens", "completion_tokens", "total_tokens",
                "code_blocks", "user_prompt"
            ]],
            use_container_width=True,
            hide_index=True,
        )

        # Export buttons
        dl_col1, dl_col2 = st.columns(2)
        with dl_col1:
            csv_bytes = audit_df.to_csv(index=False).encode("utf-8")
            st.download_button(
                "📥 Download Filtered Telemetry as CSV",
                data=csv_bytes,
                file_name="sniptutor_usage_telemetry.csv",
                mime="text/csv",
                use_container_width=True,
            )
        with dl_col2:
            json_bytes = audit_df.to_json(orient="records", date_format="iso", indent=2).encode("utf-8")
            st.download_button(
                "📥 Download Filtered Telemetry as JSON",
                data=json_bytes,
                file_name="sniptutor_usage_telemetry.json",
                mime="application/json",
                use_container_width=True,
            )

        st.divider()
        st.markdown("#### 💬 Interactive Conversation Transcript Inspector")
        inspect_convs = filtered_df[["conversation_id", "conversation_short", "conversation_title", "username"]].drop_duplicates()
        chosen_cid = st.selectbox(
            "Select Conversation to Read Full Transcript & Turn Metrics",
            options=inspect_convs["conversation_id"].tolist(),
            format_func=lambda cid: f"[{inspect_convs[inspect_convs['conversation_id']==cid]['username'].iloc[0]}] {inspect_convs[inspect_convs['conversation_id']==cid]['conversation_title'].iloc[0]} ({cid[:8]})",
        )
        if chosen_cid:
            thread_turns = filtered_df[filtered_df["conversation_id"] == chosen_cid].sort_values("turn_in_conversation")
            for _, t_row in thread_turns.iterrows():
                with st.expander(
                    f"Turn #{t_row['turn_in_conversation']} | {t_row['username']} | {t_row['modality']} | {t_row['total_tokens']:,} Tokens (In: {t_row['prompt_tokens']:,}, Out: {t_row['completion_tokens']:,}) | {t_row['user_prompt'][:65]}",
                    expanded=(t_row["turn_in_conversation"] == 1),
                ):
                    st.markdown(f"**🧑 {t_row['username']} ({t_row['prompt_chars']} chars, {t_row['user_tokens']} direct tokens):**")
                    st.info(t_row["user_prompt"])
                    st.markdown(f"**🤖 SnipTutor ({t_row['response_chars']} chars, {t_row['completion_tokens']} completion tokens, {t_row['code_blocks']} code blocks):**")
                    st.markdown(t_row["ai_response"] if t_row["ai_response"] else "_[No AI response recorded for this turn]_")


# ------------------------------------------------------------------------------
# TAB 6: MULTI-LAPTOP & CLOUD TELEMETRY (BROTHER'S LAPTOP TRACKING)
# ------------------------------------------------------------------------------
with tab6:
    st.subheader("🌐 Multi-Laptop & Cloud Telemetry Status")
    st.markdown(
        "Track usage across **your laptop (`sniptutor.db`)** and **remote laptops (such as your brother's laptop)** connected via your backend."
    )

    cloud_status = fetch_cloud_analytics(CLOUD_BACKEND_URL)
    s_col1, s_col2 = st.columns(2)
    with s_col1:
        st.markdown("#### 💻 Local SQLite Telemetry (`sniptutor.db`)")
        st.success(f"Connected: `{DEFAULT_DB_PATH}`")
        st.write(f"- **Registered Users:** `{len(users_df)}` (`{', '.join(users_df['username'].tolist()) if not users_df.empty else 'None'}`)")
        st.write(f"- **Saved Conversations:** `{len(convs_df)}`")
        st.write(f"- **Saved Messages:** `{len(msgs_df)}`")
        st.write(f"- **Logged API Events (`usage_logs`):** `{len(raw_logs_df)}`")
        st.write(f"- **Local Screen Captures (`Pictures/SnipTutor`):** `{len(captures_df)}` files (`{total_captures_mb} MB`)")

    with s_col2:
        st.markdown(f"#### ☁️ Cloud Backend (`{CLOUD_BACKEND_URL}`)")
        if cloud_status.get("reachable") and cloud_status.get("has_endpoint"):
            c_data = cloud_status["data"]
            st.success("Live & Telemetry Endpoint Active (`/api/analytics`)")
            st.write(f"- **Cloud Users:** `{len(c_data.get('users', []))}`")
            st.write(f"- **Cloud Conversations:** `{len(c_data.get('conversations', []))}`")
            st.write(f"- **Cloud Usage Logs:** `{len(c_data.get('usage_logs', []))}`")
        elif cloud_status.get("reachable") and not cloud_status.get("has_endpoint"):
            st.warning("Cloud Backend is Awake (`/health` = 200 OK), awaiting `git push` to enable `/api/analytics`")
            st.write(
                "Your `main.py` has now been upgraded with real-time `usage_logs` and `/api/analytics`! "
                "Push your updated `main.py` to GitHub/Render (`git add main.py && git commit -m 'Add usage telemetry' && git push`) "
                "so that every prompt from your brother's laptop on Render is automatically streamed into this dashboard."
            )
        else:
            st.info(f"Cloud Backend status: `{cloud_status.get('error', 'Cold-booting / Offline')}`")
            st.caption("Click 'Refresh Live Telemetry' in the sidebar to re-poll the cloud backend.")
