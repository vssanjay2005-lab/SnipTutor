from fastapi import FastAPI, File, UploadFile, Form, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel
import os
import io
import re
import time
import math
import shutil
import secrets
import sqlite3
import hashlib
import urllib.request
from typing import Optional, List, Dict, Any, Tuple
from dotenv import load_dotenv
from google import genai
from google.genai import types
import cv2
import pytesseract
import numpy as np
import pypdf
import docx
import pptx
import pandas as pd
from bs4 import BeautifulSoup

import ctypes
from ctypes import wintypes

class RECT(ctypes.Structure):
    _fields_ = [
        ('left', wintypes.LONG),
        ('top', wintypes.LONG),
        ('right', wintypes.LONG),
        ('bottom', wintypes.LONG)
    ]

original_work_area = None

# Cross-platform Tesseract detection (Windows & Linux Cloud)
TESSERACT_WIN_PATH = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
if os.path.exists(TESSERACT_WIN_PATH):
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_WIN_PATH
elif shutil.which("tesseract"):
    pytesseract.pytesseract.tesseract_cmd = shutil.which("tesseract")

# Load environment variables
load_dotenv()

_gemini_client = None
_cached_working_model = None

def get_gemini_client():
    global _gemini_client
    if _gemini_client is not None:
        return _gemini_client
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise RuntimeError("GEMINI_API_KEY is not set. Add it to your .env file or cloud environment variables.")
    _gemini_client = genai.Client(api_key=api_key)
    return _gemini_client

INSTANT_GREETINGS = {
    "hi": "Hi there! 👋 I'm **SnipTutor**, your AI coding and study companion. Ask me anything, paste code, or snip your screen (`Ctrl + Shift + Space`) and I'll help right away!",
    "hey": "Hey! 👋 Ready to help with code, debugging, or anything on your screen. What are we working on?",
    "hello": "Hello! 👋 How can I help you today? You can ask a question, attach a file, or snip any part of your screen.",
    "hola": "¡Hola! 👋 How can I help you with your code or studies today?",
    "yo": "Yo! 👋 What are we building or debugging today?",
    "sup": "Hey! 👋 Ready when you are — drop a question or snip your screen!",
    "whats up": "Ready to help you debug, code, or learn! What's on your mind?",
    "what's up": "Ready to help you debug, code, or learn! What's on your mind?",
    "how are you": "I'm running at full speed and ready to help! ⚡ What are we working on today?",
    "good morning": "Good morning! ☀️ What are we learning or building today?",
    "good afternoon": "Good afternoon! 👋 How can I assist you right now?",
    "good evening": "Good evening! 🌙 Ready to help you wrap up your tasks or debug code.",
    "thanks": "You're very welcome! Let me know if you need anything else. ✨",
    "thank you": "Happy to help! Feel free to ask anytime. ✨",
    "thx": "Anytime! 👍 Let me know what's next.",
    "ok": "Got it! Let me know whenever you're ready for the next step.",
    "okay": "Sounds good! Ask away whenever you're ready.",
    "bye": "See you later! Good luck with your project! 👋",
}

def get_instant_greeting_reply(user_text: Optional[str]) -> Optional[str]:
    if not user_text:
        return None
    cleaned = re.sub(r"[^\w\s']", "", user_text.strip().lower())
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    if cleaned in INSTANT_GREETINGS:
        return INSTANT_GREETINGS[cleaned]
    if cleaned in ("hi sniptutor", "hey sniptutor", "hello sniptutor", "hi there", "hey there", "hello there"):
        return INSTANT_GREETINGS["hi"]
    return None

app = FastAPI(title="SnipTutor API", version="2.0.0")

# Enable CORS for local & cloud deployment
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.post("/set-os-workarea/")
def set_os_workarea(dock_width: int = 370):
    global original_work_area
    if os.name != "nt":
        return {"status": "skipped", "reason": "OS workarea partitioning is only applicable on Windows desktop"}
    try:
        user32 = ctypes.windll.user32
        curr_rect = RECT()
        # SPI_GETWORKAREA = 0x0030
        user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(curr_rect), 0)
        
        if original_work_area is None:
            original_work_area = (curr_rect.left, curr_rect.top, curr_rect.right, curr_rect.bottom)
            
        screen_width = user32.GetSystemMetrics(0) # SM_CXSCREEN
        screen_height = user32.GetSystemMetrics(1) # SM_CYSCREEN
        new_right = max(600, screen_width - dock_width)
        
        new_rect = RECT(curr_rect.left, curr_rect.top, new_right, curr_rect.bottom)
        # 1. Update OS WorkArea: SPI_SETWORKAREA = 0x002F, SPIF_UPDATEINIFILE = 0x0001, SPIF_SENDCHANGE = 0x0002
        user32.SystemParametersInfoW(0x002F, 0, ctypes.byref(new_rect), 0x0002 | 0x0001)

        # 2. Broadcast WM_SETTINGCHANGE (0x001A) to HWND_BROADCAST (0xFFFF)
        try:
            user32.SendMessageTimeoutW(0xFFFF, 0x001A, 0x002F, ctypes.c_wchar_p("SPI_SETWORKAREA"), 0x0002, 150, ctypes.byref(ctypes.c_ulong()))
        except Exception:
            pass

        return {"status": "ok", "new_right": new_right, "dock_width": dock_width}
    except Exception as e:
        return {"status": "error", "error": str(e)}

@app.post("/restore-os-workarea/")
def restore_os_workarea():
    global original_work_area
    if os.name != "nt":
        return {"status": "skipped"}
    try:
        if original_work_area is None:
            return {"status": "already_restored"}
        user32 = ctypes.windll.user32
        restore_rect = RECT(*original_work_area)
        # SPI_SETWORKAREA = 0x002F
        user32.SystemParametersInfoW(0x002F, 0, ctypes.byref(restore_rect), 0x0002 | 0x0001)
        original_work_area = None

        # Broadcast WM_SETTINGCHANGE
        try:
            user32.SendMessageTimeoutW(0xFFFF, 0x001A, 0x002F, ctypes.c_wchar_p("SPI_SETWORKAREA"), 0x0002, 150, ctypes.byref(ctypes.c_ulong()))
        except Exception:
            pass

        return {"status": "restored"}
    except Exception as e:
        return {"status": "error", "error": str(e)}

# ==============================================================================
# GEMINI FLASH NATURAL RESPONSE ARCHITECTURE
# ==============================================================================

SYSTEM_CORE_PROMPT = """You are SnipTutor, an intelligent, helpful AI tutor and senior developer companion powered by Gemini.

RESPONSE STYLE & FORMATTING GUIDELINES (Replicate Google Gemini Flash):
1. Adapt naturally to the user's intent:
   - For greetings / casual chit-chat (e.g., 'hi', 'hello', 'thanks', 'how are you'): Respond naturally, warmly, and concisely in 1-2 friendly sentences. Do NOT generate analogies, long essays, or follow-up questions for casual greetings.
   - For direct questions, coding tasks, bug fixes, or assignments: Provide the direct, accurate solution immediately, with clean code blocks and concise technical explanations without unnecessary fluff.
   - For learning concepts or theory: Provide a clear, well-structured, easy-to-read explanation with clean formatting (bold key terms, code examples, bullet points). Do NOT force rigid or artificial section headers like 'Simple Analogy'. Explain directly, concisely, and conversationally.
2. If screenshot OCR or file context is attached:
   - If the user explicitly asks about the screen, file, or an error: Analyze the context thoroughly and provide the exact solution.
   - If the user just says a greeting like 'hi' or asks an unrelated general question: Respond to the user's actual question directly without dumping an unsolicited breakdown of their desktop background.
3. Follow-up Suggestions (Only for substantive questions / concept exploration / technical tasks):
   - At the very end of explanations or technical answers (NOT casual greetings or simple closures), provide 2-3 concise, clickable follow-up questions or next steps tailored specifically to what was discussed.
   - Format them strictly as:
### 💬 Follow-up Questions
* 💡 [Specific follow-up exploration or practical application]
* 🔍 [Specific follow-up on deeper mechanics or edge cases]
* ⚡ [Specific follow-up on alternative approaches or optimizations]
"""

def _generate_fast(gemini_client, model_name: str, contents_payload):
    """Call Gemini with low-latency settings, avoiding thinking config errors on flash-lite models."""
    if "3.8" in model_name or "thinking" in model_name:
        try:
            fast_config = types.GenerateContentConfig(
                thinking_config=types.ThinkingConfig(thinking_budget=0)
            )
            return gemini_client.models.generate_content(
                model=model_name,
                contents=contents_payload,
                config=fast_config,
            )
        except Exception:
            pass
    return gemini_client.models.generate_content(
        model=model_name,
        contents=contents_payload,
    )

def call_gemini_with_metrics(
    prompt: str,
    image_bytes: Optional[bytes] = None,
    mime_type: str = "image/png"
) -> Tuple[str, Dict[str, Any]]:
    global _cached_working_model
    gemini_client = get_gemini_client()
    base_models = [
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
    ]
    if _cached_working_model and _cached_working_model in base_models:
        models_to_try = [_cached_working_model] + [m for m in base_models if m != _cached_working_model]
    else:
        models_to_try = base_models

    contents_payload = (
        [types.Part.from_bytes(data=image_bytes, mime_type=mime_type), prompt]
        if image_bytes
        else prompt
    )
    last_error = None
    t0 = time.perf_counter()
    for model_name in models_to_try:
        try:
            response = _generate_fast(gemini_client, model_name, contents_payload)
            if response and response.text:
                _cached_working_model = model_name
                latency_ms = round((time.perf_counter() - t0) * 1000.0, 2)
                usage = getattr(response, "usage_metadata", None)
                p_tok = getattr(usage, "prompt_token_count", None) if usage else None
                c_tok = getattr(usage, "candidates_token_count", None) if usage else None
                t_tok = getattr(usage, "total_token_count", None) if usage else None
                if not p_tok:
                    p_tok = math.ceil(len(prompt) / 4.0) + (258 if image_bytes else 0)
                if not c_tok:
                    c_tok = math.ceil(len(response.text) / 4.0)
                if not t_tok:
                    t_tok = p_tok + c_tok
                return response.text, {
                    "model_used": model_name,
                    "prompt_tokens": int(p_tok),
                    "completion_tokens": int(c_tok),
                    "total_tokens": int(t_tok),
                    "latency_ms": latency_ms,
                }
        except Exception as e:
            last_error = e
            continue

    # If multimodal image payload failed across models, fall back to prompt + OCR text
    if image_bytes is not None:
        for model_name in models_to_try:
            try:
                response = _generate_fast(gemini_client, model_name, prompt)
                if response and response.text:
                    _cached_working_model = model_name
                    latency_ms = round((time.perf_counter() - t0) * 1000.0, 2)
                    usage = getattr(response, "usage_metadata", None)
                    p_tok = getattr(usage, "prompt_token_count", None) if usage else None
                    c_tok = getattr(usage, "candidates_token_count", None) if usage else None
                    t_tok = getattr(usage, "total_token_count", None) if usage else None
                    if not p_tok:
                        p_tok = math.ceil(len(prompt) / 4.0)
                    if not c_tok:
                        c_tok = math.ceil(len(response.text) / 4.0)
                    if not t_tok:
                        t_tok = p_tok + c_tok
                    return response.text, {
                        "model_used": model_name,
                        "prompt_tokens": int(p_tok),
                        "completion_tokens": int(c_tok),
                        "total_tokens": int(t_tok),
                        "latency_ms": latency_ms,
                    }
            except Exception as e:
                last_error = e
                continue

    raise last_error if last_error else RuntimeError("Failed to generate response from Gemini API")

def call_gemini(prompt: str, image_bytes: Optional[bytes] = None, mime_type: str = "image/png") -> str:
    text, _ = call_gemini_with_metrics(prompt, image_bytes=image_bytes, mime_type=mime_type)
    return text

WEB_INDEX_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web", "index.html")

@app.get("/")
def read_root(request: Request):
    accept = request.headers.get("accept", "")
    if "text/html" in accept and os.path.exists(WEB_INDEX_PATH):
        with open(WEB_INDEX_PATH, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    return {"status": "ok", "message": "Hello, SnipTutor is running with Gemini & Multi-Media Support!"}

@app.get("/app", response_class=HTMLResponse)
def serve_web_app():
    if os.path.exists(WEB_INDEX_PATH):
        with open(WEB_INDEX_PATH, "r", encoding="utf-8") as f:
            return HTMLResponse(content=f.read())
    return HTMLResponse(content="<h1>SnipTutor Web UI not found</h1>", status_code=404)

@app.get("/health")
def health_check():
    return {"status": "ok", "service": "SnipTutor API"}

class TextInput(BaseModel):
    text: str
    history: Optional[str] = None
    mode: Optional[str] = "Student"
    user_id: Optional[int] = None
    username: Optional[str] = None

@app.post("/process-text/")
def process_text(input: TextInput, request: Request):
    try:
        instant_reply = get_instant_greeting_reply(input.text)
        if instant_reply:
            fast_metrics = {
                "model_used": "gemini-3.5-flash-fastpath",
                "prompt_tokens": max(1, math.ceil(len(input.text or "") / 4.0)),
                "completion_tokens": math.ceil(len(instant_reply) / 4.0),
                "total_tokens": max(1, math.ceil((len(input.text or "") + len(instant_reply)) / 4.0)),
                "latency_ms": 4.2,
            }
            return {"input": input.text, "gemini_response": instant_reply, "usage": fast_metrics}

        mode_instruction = (
            "Mode: Student (Provide comprehensive, high-scoring, clear pedagogical solutions and detailed explanations.)"
            if input.mode == "Student"
            else "Mode: Developer (Focus on concise, fast-paced technical engineering solutions and production code.)"
        )

        context_prompt = f"""{SYSTEM_CORE_PROMPT}

{mode_instruction}

--- CONVERSATION HISTORY ---
{input.history if input.history else "[Start of conversation]"}

--- USER QUESTION / PROMPT ---
{input.text}

Please provide your comprehensive SnipTutor response:"""

        response_text, metrics = call_gemini_with_metrics(context_prompt)
        log_usage_event(
            endpoint="/process-text/",
            mode=input.mode or "Student",
            user_prompt=input.text,
            history_text=input.history or "",
            response_text=response_text,
            attachment_type="Text",
            media_tokens=0,
            metrics=metrics,
            client_ip=request.client.host if (request and request.client) else "127.0.0.1",
            user_id=input.user_id,
            username=input.username,
        )
        return {"input": input.text, "gemini_response": response_text, "usage": metrics}
    except Exception as e:
        return {"error": str(e)}

@app.post("/process-image/")
async def process_image(
    request: Request,
    file: UploadFile = File(...),
    prompt: Optional[str] = Form(None),
    mode: Optional[str] = Form("Student"),
    history: Optional[str] = Form(None),
    user_id: Optional[int] = Form(None),
    username: Optional[str] = Form(None)
):
    try:
        # Instant fast-path if the user just typed a greeting while Live Screen Context was active
        instant_reply = get_instant_greeting_reply(prompt)
        if instant_reply:
            fast_metrics = {
                "model_used": "gemini-3.5-flash-fastpath",
                "prompt_tokens": max(1, math.ceil(len(prompt or "") / 4.0)),
                "completion_tokens": math.ceil(len(instant_reply) / 4.0),
                "total_tokens": max(1, math.ceil((len(prompt or "") + len(instant_reply)) / 4.0)),
                "latency_ms": 5.0,
            }
            return {
                "extracted_text": "",
                "user_question": prompt,
                "gemini_response": instant_reply,
                "usage": fast_metrics,
            }

        contents = await file.read()
        extracted_text = ""
        optimized_bytes = contents
        mime_type = file.content_type if (file.content_type and file.content_type.startswith("image/")) else "image/png"

        # Fast preprocessing: resize & JPEG-encode in ~10ms for fast multimodal Gemini Vision upload (skip slow synchronous CPU Tesseract OCR)
        try:
            nparr = np.frombuffer(contents, np.uint8)
            img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            if img is not None:
                h, w = img.shape[:2]
                if w > 1280:
                    scale = 1280.0 / w
                    resized = cv2.resize(img, (1280, int(h * scale)), interpolation=cv2.INTER_AREA)
                else:
                    resized = img
                success, encoded_jpg = cv2.imencode(".jpg", resized, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
                if success:
                    optimized_bytes = encoded_jpg.tobytes()
                    mime_type = "image/jpeg"
        except Exception:
            pass

        user_question = prompt.strip() if (prompt and prompt.strip()) else "Please analyze what is shown in this screenshot and provide a complete, direct solution or breakdown."

        mode_instruction = (
            "Mode: Student (Provide comprehensive, high-scoring, clear pedagogical solutions and detailed explanations.)"
            if mode == "Student"
            else "Mode: Developer (Focus on concise, fast-paced technical engineering solutions and production code.)"
        )

        full_prompt = f"""{SYSTEM_CORE_PROMPT}

{mode_instruction}

--- CONVERSATION HISTORY ---
{history if history else "[Start of conversation]"}

--- SCREENSHOT CONTEXT ---
[Analyze the attached screenshot image directly using your native vision capabilities]

--- USER QUESTION ABOUT SCREENSHOT ---
{user_question}

Please provide your comprehensive SnipTutor response:"""

        response_text, metrics = call_gemini_with_metrics(full_prompt, image_bytes=optimized_bytes, mime_type=mime_type)
        fname = (file.filename or "screenshot.png")
        att_type = "Live Screen Context" if "LiveContext" in fname else "Screen Snip / Image"
        media_toks = 258
        log_usage_event(
            endpoint="/process-image/",
            mode=mode or "Student",
            user_prompt=user_question,
            history_text=history or "",
            response_text=response_text,
            attachment_type=att_type,
            media_tokens=media_toks,
            metrics=metrics,
            client_ip=request.client.host if request.client else "127.0.0.1",
            user_id=user_id,
            username=username,
        )

        return {
            "extracted_text": extracted_text,
            "user_question": user_question,
            "gemini_response": response_text,
            "usage": metrics
        }
    except Exception as e:
        return {"error": str(e)}

# ==============================================================================
# UNIVERSAL FILE & MEDIA EXTRACTION PIPELINE
# ==============================================================================

def extract_file_content(file_bytes: bytes, filename: str) -> str:
    ext = os.path.splitext(filename)[1].lower()
    
    # 1. PDF
    if ext == ".pdf":
        try:
            reader = pypdf.PdfReader(io.BytesIO(file_bytes))
            pages = []
            max_pages = min(len(reader.pages), 30)
            for i in range(max_pages):
                text = reader.pages[i].extract_text()
                if text and text.strip():
                    pages.append(f"--- Page {i+1} ---\n{text.strip()}")
            return "\n\n".join(pages) if pages else "[PDF contains scanned pages or images without text layer]"
        except Exception as e:
            return f"[Error extracting PDF: {str(e)}]"

    # 2. Word Documents (.docx, .doc)
    elif ext in [".docx", ".doc"]:
        try:
            doc = docx.Document(io.BytesIO(file_bytes))
            text_blocks = [p.text.strip() for p in doc.paragraphs if p.text.strip()]
            for table in doc.tables:
                for row in table.rows:
                    row_txt = " | ".join(cell.text.strip() for cell in row.cells if cell.text.strip())
                    if row_txt:
                        text_blocks.append(row_txt)
            return "\n".join(text_blocks)
        except Exception as e:
            return f"[Error extracting DOCX: {str(e)}]"

    # 3. PowerPoint Presentations (.pptx, .ppt)
    elif ext in [".pptx", ".ppt"]:
        try:
            prs = pptx.Presentation(io.BytesIO(file_bytes))
            slides_text = []
            for i, slide in enumerate(prs.slides):
                slide_shapes = []
                for shape in slide.shapes:
                    if shape.has_text_frame:
                        for p in shape.text_frame.paragraphs:
                            if p.text.strip():
                                slide_shapes.append(p.text.strip())
                if slide_shapes:
                    slides_text.append(f"--- Slide {i+1} ---\n" + "\n".join(slide_shapes))
            return "\n\n".join(slides_text)
        except Exception as e:
            return f"[Error extracting PPTX: {str(e)}]"

    # 4. Spreadsheets (.xlsx, .xls, .csv)
    elif ext in [".xlsx", ".xls"]:
        try:
            excel_data = pd.read_excel(io.BytesIO(file_bytes), sheet_name=None)
            sheets_summary = []
            for sheet_name, df in excel_data.items():
                sheets_summary.append(f"--- Sheet: {sheet_name} (Shape: {df.shape[0]} rows x {df.shape[1]} cols) ---")
                sheets_summary.append(f"Columns: {', '.join(df.columns.astype(str))}")
                sheets_summary.append("Data Sample:\n" + df.head(15).to_string())
            return "\n\n".join(sheets_summary)
        except Exception as e:
            return f"[Error extracting Excel: {str(e)}]"
            
    elif ext == ".csv":
        try:
            df = pd.read_csv(io.BytesIO(file_bytes))
            return f"--- CSV Dataset (Shape: {df.shape[0]} rows x {df.shape[1]} cols) ---\nColumns: {', '.join(df.columns.astype(str))}\nData Sample:\n{df.head(15).to_string()}"
        except Exception as e:
            return f"[Error extracting CSV: {str(e)}]"

    # 5. Images (.png, .jpg, .jpeg, .bmp, .webp, .gif)
    elif ext in [".png", ".jpg", ".jpeg", ".bmp", ".webp", ".gif"]:
        try:
            nparr = np.frombuffer(file_bytes, np.uint8)
            img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            if img is not None:
                gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
                ocr_text = pytesseract.image_to_string(gray, config='--oem 3 --psm 6').strip()
                return f"[Image Media File: {filename}]\nExtracted OCR Text:\n{ocr_text if ocr_text else '[Visual content with no clear text detected]'}"
            return f"[Image Media: {filename}]"
        except Exception as e:
            return f"[Error processing image: {str(e)}]"

    # 6. Plain Text / Code / Markdown / JSON / Scripts
    else:
        try:
            decoded = file_bytes.decode('utf-8')
            return decoded[:50000] # Limit to 50K chars to stay well within context
        except UnicodeDecodeError:
            try:
                decoded = file_bytes.decode('latin-1')
                return decoded[:50000]
            except Exception:
                return f"[Binary file media: {filename} ({len(file_bytes)} bytes)]"

@app.post("/process-file/")
async def process_file(
    request: Request,
    file: UploadFile = File(...),
    prompt: Optional[str] = Form(None),
    mode: Optional[str] = Form("Student"),
    history: Optional[str] = Form(None),
    user_id: Optional[int] = Form(None),
    username: Optional[str] = Form(None)
):
    try:
        file_bytes = await file.read()
        filename = file.filename or "attachment"
        ext = os.path.splitext(filename)[1].lower()
        is_image = ext in [".png", ".jpg", ".jpeg", ".bmp", ".webp", ".gif"]
        extracted_content = extract_file_content(file_bytes, filename)
        user_question = prompt.strip() if (prompt and prompt.strip()) else f"Please analyze this attached file ({filename}) and provide a complete solution, breakdown, or answers."

        mode_instruction = (
            "Mode: Student (Provide comprehensive, high-scoring, clear pedagogical solutions and detailed explanations.)"
            if mode == "Student"
            else "Mode: Developer (Focus on concise, fast-paced technical engineering solutions and production code.)"
        )

        full_prompt = f"""{SYSTEM_CORE_PROMPT}

{mode_instruction}

--- CONVERSATION HISTORY ---
{history if history else "[Start of conversation]"}

--- ATTACHED FILE CONTEXT ({filename}) ---
{extracted_content}

--- USER QUESTION ABOUT FILE ---
{user_question}

Please provide your comprehensive SnipTutor response:"""

        if is_image:
            mime_map = {
                ".png": "image/png",
                ".jpg": "image/jpeg",
                ".jpeg": "image/jpeg",
                ".webp": "image/webp",
                ".gif": "image/gif",
                ".bmp": "image/bmp",
            }
            response_text, metrics = call_gemini_with_metrics(full_prompt, image_bytes=file_bytes, mime_type=mime_map.get(ext, "image/png"))
            media_toks = 258 + math.ceil(len(extracted_content) / 4.0)
        else:
            response_text, metrics = call_gemini_with_metrics(full_prompt)
            media_toks = math.ceil(len(extracted_content) / 4.0)

        log_usage_event(
            endpoint="/process-file/",
            mode=mode or "Student",
            user_prompt=user_question,
            history_text=history or "",
            response_text=response_text,
            attachment_type=f"Document ({ext or 'file'})",
            media_tokens=media_toks,
            metrics=metrics,
            client_ip=request.client.host if request.client else "127.0.0.1",
            user_id=user_id,
            username=username,
        )

        return {
            "filename": filename,
            "extracted_length": len(extracted_content),
            "gemini_response": response_text,
            "usage": metrics
        }
    except Exception as e:
        return {"error": str(e)}

class UrlRequest(BaseModel):
    url: str
    prompt: Optional[str] = None
    mode: Optional[str] = "Student"
    history: Optional[str] = None
    user_id: Optional[int] = None
    username: Optional[str] = None

@app.post("/process-url/")
def process_url(req: UrlRequest, request: Request):
    try:
        url = req.url.strip()
        if not url.startswith("http://") and not url.startswith("https://"):
            url = "https://" + url

        http_req = urllib.request.Request(
            url,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
        )
        with urllib.request.urlopen(http_req, timeout=12) as response:
            html = response.read().decode('utf-8', errors='replace')

        soup = BeautifulSoup(html, 'html.parser')
        for tag in soup(["script", "style", "nav", "footer", "header", "noscript"]):
            tag.decompose()

        page_title = soup.title.string.strip() if (soup.title and soup.title.string) else url
        clean_text = soup.get_text(separator="\n")
        lines = [line.strip() for line in clean_text.splitlines() if line.strip()]
        page_body = "\n".join(lines)[:35000]

        user_question = req.prompt.strip() if (req.prompt and req.prompt.strip()) else f"Please explain the content of this webpage ({page_title}) and address the key requirements or questions."

        mode_instruction = (
            "Mode: Student (Provide comprehensive, high-scoring, clear pedagogical solutions and detailed explanations.)"
            if req.mode == "Student"
            else "Mode: Developer (Focus on concise, fast-paced technical engineering solutions and production code.)"
        )

        full_prompt = f"""{SYSTEM_CORE_PROMPT}

{mode_instruction}

--- CONVERSATION HISTORY ---
{req.history if req.history else "[Start of conversation]"}

--- WEBPAGE TITLE: {page_title} ---
--- WEBPAGE URL: {url} ---
--- WEBPAGE CONTENT EXTRACT ---
{page_body}

--- USER QUESTION ABOUT URL ---
{user_question}

Please provide your comprehensive SnipTutor response:"""

        response_text, metrics = call_gemini_with_metrics(full_prompt)
        log_usage_event(
            endpoint="/process-url/",
            mode=req.mode or "Student",
            user_prompt=user_question,
            history_text=req.history or "",
            response_text=response_text,
            attachment_type="Web URL",
            media_tokens=math.ceil(len(page_body) / 4.0),
            metrics=metrics,
            client_ip=request.client.host if request.client else "127.0.0.1",
            user_id=req.user_id,
            username=req.username,
        )
        return {
            "url": url,
            "title": page_title,
            "gemini_response": response_text,
            "usage": metrics
        }
    except Exception as e:
        return {"error": f"Failed to fetch and analyze URL: {str(e)}"}

# ==============================================================================
# DATABASE SETUP & USER AUTHENTICATION / CONVERSATION PERSISTENCE / TELEMETRY
# ==============================================================================
DB_PATH = os.getenv("SNIPTUTOR_DB_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "sniptutor.db"))

def get_db_connection():
    conn = sqlite3.connect(DB_PATH, timeout=15.0)
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA foreign_keys=ON;")
    return conn

def backfill_historical_usage_logs(conn: sqlite3.Connection):
    cursor = conn.cursor()
    existing_count = cursor.execute("SELECT COUNT(*) FROM usage_logs").fetchone()[0]
    if existing_count > 0:
        return

    convs = cursor.execute("""
        SELECT c.id, c.user_id, u.username, c.title, c.created_at, c.updated_at
        FROM conversations c
        LEFT JOIN users u ON c.user_id = u.id
        ORDER BY c.created_at ASC
    """).fetchall()

    sys_overhead_tokens = 400
    for cid, uid, uname, title, c_created, c_updated in convs:
        uname = uname or f"User-{uid}"
        msgs = cursor.execute(
            "SELECT id, sender, text, image_path, timestamp FROM messages WHERE conversation_id=? ORDER BY id ASC",
            (cid,)
        ).fetchall()
        history_chars = 0
        i = 0
        turn_idx = 0
        while i < len(msgs):
            m = msgs[i]
            if m[1] == "You":
                u_text = m[2] or ""
                img_path = m[3]
                msg_ts = m[4] or c_updated or c_created
                ai_text = ""
                if i + 1 < len(msgs) and msgs[i + 1][1] == "SnipTutor":
                    ai_text = msgs[i + 1][2] or ""
                    i += 2
                else:
                    i += 1
                turn_idx += 1

                u_tok = max(1, math.ceil(len(u_text) / 4.0))
                hist_tok = math.ceil(history_chars / 4.0)
                sys_tok = sys_overhead_tokens

                is_doc = ("[Attached:" in u_text or "📁" in u_text)
                is_img = (img_path is not None) or (
                    "screenshot" in ai_text.lower()
                    or "screen" in ai_text.lower()
                    or "whatt do u see" in u_text.lower()
                    or "what to u see" in u_text.lower()
                    or "reading a book" in u_text.lower()
                    or "what is the answer" in u_text.lower()
                )
                if is_doc:
                    att_type = "Document (.docx)"
                    endpoint = "/process-file/"
                    vis_tok = 1200
                elif img_path is not None:
                    att_type = "Screen Snip / Image"
                    endpoint = "/process-image/"
                    vis_tok = 258 + 160
                elif is_img:
                    att_type = "Live Screen Context"
                    endpoint = "/process-image/"
                    vis_tok = 258 + 160
                else:
                    att_type = "Text"
                    endpoint = "/process-text/"
                    vis_tok = 0

                in_tok = u_tok + hist_tok + sys_tok + vis_tok
                out_tok = math.ceil(len(ai_text) / 4.0) if ai_text else 0
                tot_tok = in_tok + out_tok
                code_blocks = len(re.findall(r"```", ai_text)) // 2
                mode_inferred = "Developer" if (code_blocks >= 2 or "code" in u_text.lower() or "program" in u_text.lower()) else "Student"
                est_latency = round(850.0 + out_tok * 3.2 + (420.0 if vis_tok > 0 else 0.0), 1)

                cursor.execute("""
                    INSERT INTO usage_logs (
                        timestamp, user_id, username, client_ip, conversation_id,
                        endpoint, mode, model_used, prompt_chars, response_chars,
                        user_tokens, system_tokens, history_tokens, media_tokens,
                        prompt_tokens, completion_tokens, total_tokens,
                        latency_ms, attachment_type, code_blocks_count, is_backfilled
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, (
                    msg_ts, uid, uname, "127.0.0.1", cid,
                    endpoint, mode_inferred, "gemini-3.8-flash", len(u_text), len(ai_text),
                    u_tok, sys_tok, hist_tok, vis_tok,
                    in_tok, out_tok, tot_tok,
                    est_latency, att_type, code_blocks
                ))

                history_chars += len(f"You: {u_text}\n") + (len(f"SnipTutor: {ai_text}\n") if ai_text else 0)
            else:
                i += 1
    conn.commit()

def init_db():
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS conversations (
            id TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL,
            title TEXT NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation_id TEXT NOT NULL,
            sender TEXT NOT NULL,
            text TEXT NOT NULL,
            image_path TEXT,
            timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS usage_logs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            user_id INTEGER,
            username TEXT DEFAULT 'Guest',
            client_ip TEXT DEFAULT '127.0.0.1',
            conversation_id TEXT,
            endpoint TEXT NOT NULL,
            mode TEXT DEFAULT 'Student',
            model_used TEXT DEFAULT 'gemini-3.8-flash',
            prompt_chars INTEGER DEFAULT 0,
            response_chars INTEGER DEFAULT 0,
            user_tokens INTEGER DEFAULT 0,
            system_tokens INTEGER DEFAULT 400,
            history_tokens INTEGER DEFAULT 0,
            media_tokens INTEGER DEFAULT 0,
            prompt_tokens INTEGER DEFAULT 0,
            completion_tokens INTEGER DEFAULT 0,
            total_tokens INTEGER DEFAULT 0,
            latency_ms REAL DEFAULT 0.0,
            attachment_type TEXT DEFAULT 'Text',
            code_blocks_count INTEGER DEFAULT 0,
            is_backfilled INTEGER DEFAULT 0
        )
    """)
    conn.commit()
    try:
        backfill_historical_usage_logs(conn)
    except Exception:
        pass
    conn.close()

init_db()

def log_usage_event(
    endpoint: str,
    mode: str,
    user_prompt: str,
    history_text: str,
    response_text: str,
    attachment_type: str,
    media_tokens: int,
    metrics: Dict[str, Any],
    client_ip: str = "127.0.0.1",
    user_id: Optional[int] = None,
    username: Optional[str] = None,
    conversation_id: Optional[str] = None,
):
    try:
        conn = get_db_connection()
        cursor = conn.cursor()
        u_tok = max(1, math.ceil(len(user_prompt or "") / 4.0))
        sys_tok = 400
        hist_tok = math.ceil(len(history_text or "") / 4.0)
        p_tok = metrics.get("prompt_tokens") or (u_tok + sys_tok + hist_tok + media_tokens)
        c_tok = metrics.get("completion_tokens") or math.ceil(len(response_text or "") / 4.0)
        t_tok = metrics.get("total_tokens") or (p_tok + c_tok)
        code_blocks = len(re.findall(r"```", response_text or "")) // 2
        resolved_username = username or ("Guest" if not user_id else f"User-{user_id}")
        cursor.execute("""
            INSERT INTO usage_logs (
                user_id, username, client_ip, conversation_id,
                endpoint, mode, model_used, prompt_chars, response_chars,
                user_tokens, system_tokens, history_tokens, media_tokens,
                prompt_tokens, completion_tokens, total_tokens,
                latency_ms, attachment_type, code_blocks_count, is_backfilled
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """, (
            user_id, resolved_username, client_ip, conversation_id,
            endpoint, mode, metrics.get("model_used", "gemini-3.8-flash"),
            len(user_prompt or ""), len(response_text or ""),
            u_tok, sys_tok, hist_tok, media_tokens,
            int(p_tok), int(c_tok), int(t_tok),
            float(metrics.get("latency_ms", 0.0)),
            attachment_type, code_blocks
        ))
        conn.commit()
        conn.close()
    except Exception:
        pass

def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), 100_000)
    return f"pbkdf2${salt}${dk.hex()}"

def verify_password(password: str, stored_hash: str) -> bool:
    if stored_hash.startswith("pbkdf2$"):
        parts = stored_hash.split("$")
        if len(parts) == 3:
            salt = parts[1]
            expected = parts[2]
            dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode("utf-8"), 100_000)
            return secrets.compare_digest(dk.hex(), expected)
    # Legacy SHA-256 fallback for existing accounts
    legacy_hash = hashlib.sha256(password.encode("utf-8")).hexdigest()
    return secrets.compare_digest(legacy_hash, stored_hash)

class AuthRequest(BaseModel):
    username: str
    password: str

@app.post("/auth/register/")
def register_user(req: AuthRequest):
    username = req.username.strip()
    if not username or len(username) < 3:
        return {"status": "error", "message": "Username must be at least 3 characters long."}
    if not req.password or len(req.password) < 4:
        return {"status": "error", "message": "Password must be at least 4 characters long."}
    
    conn = get_db_connection()
    cursor = conn.cursor()
    try:
        pw_hash = hash_password(req.password)
        cursor.execute("INSERT INTO users (username, password_hash) VALUES (?, ?)", (username, pw_hash))
        user_id = cursor.lastrowid
        conn.commit()
        return {"status": "ok", "user_id": user_id, "username": username}
    except sqlite3.IntegrityError:
        return {"status": "error", "message": "Username already exists. Please login or choose another name."}
    except Exception as e:
        return {"status": "error", "message": str(e)}
    finally:
        conn.close()

@app.post("/auth/login/")
def login_user(req: AuthRequest):
    username = req.username.strip()
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT id, username, password_hash FROM users WHERE username = ?", (username,))
    user = cursor.fetchone()
    conn.close()
    
    if user and verify_password(req.password, user[2]):
        return {"status": "ok", "user_id": user[0], "username": user[1]}
    else:
        return {"status": "error", "message": "Invalid username or password."}

@app.get("/conversations/")
def get_conversations(user_id: int):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT id, title, updated_at FROM conversations WHERE user_id = ? ORDER BY updated_at DESC", (user_id,))
    conv_rows = cursor.fetchall()
    
    conversations = []
    for c_id, title, updated_at in conv_rows:
        cursor.execute("SELECT sender, text, image_path FROM messages WHERE conversation_id = ? ORDER BY id ASC", (c_id,))
        msg_rows = cursor.fetchall()
        messages = [{"sender": m[0], "text": m[1], "image_path": m[2]} for m in msg_rows]
        conversations.append({
            "id": c_id,
            "title": title,
            "messages": messages
        })
    conn.close()
    return {"status": "ok", "conversations": conversations}

class MessageData(BaseModel):
    sender: str
    text: str
    image_path: Optional[str] = None

class SaveConversationRequest(BaseModel):
    user_id: int
    conversation_id: str
    title: str
    messages: List[MessageData]

@app.post("/conversations/save/")
def save_conversation(req: SaveConversationRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    try:
        cursor.execute("""
            INSERT INTO conversations (id, user_id, title, updated_at) 
            VALUES (?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(id) DO UPDATE SET title = excluded.title, updated_at = CURRENT_TIMESTAMP
        """, (req.conversation_id, req.user_id, req.title))
        
        cursor.execute("DELETE FROM messages WHERE conversation_id = ?", (req.conversation_id,))
        for msg in req.messages:
            cursor.execute("""
                INSERT INTO messages (conversation_id, sender, text, image_path)
                VALUES (?, ?, ?, ?)
            """, (req.conversation_id, msg.sender, msg.text, msg.image_path))
        conn.commit()
        return {"status": "ok"}
    except Exception as e:
        return {"status": "error", "message": str(e)}
    finally:
        conn.close()

@app.delete("/conversations/{conversation_id}")
def delete_conversation(conversation_id: str):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("DELETE FROM messages WHERE conversation_id = ?", (conversation_id,))
    cursor.execute("DELETE FROM conversations WHERE id = ?", (conversation_id,))
    conn.commit()
    conn.close()
    return {"status": "ok"}

class RenameRequest(BaseModel):
    conversation_id: str
    new_title: str

@app.post("/conversations/rename/")
def rename_conversation(req: RenameRequest):
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("UPDATE conversations SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", (req.new_title, req.conversation_id))
    conn.commit()
    conn.close()
    return {"status": "ok"}

@app.get("/api/analytics")
def get_analytics_snapshot():
    conn = get_db_connection()
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    users = [dict(r) for r in cursor.execute("SELECT id, username, created_at FROM users ORDER BY id ASC").fetchall()]
    conversations = [dict(r) for r in cursor.execute("SELECT id, user_id, title, created_at, updated_at FROM conversations ORDER BY created_at ASC").fetchall()]
    messages = [dict(r) for r in cursor.execute("SELECT id, conversation_id, sender, text, image_path, timestamp FROM messages ORDER BY id ASC").fetchall()]
    usage_logs = [dict(r) for r in cursor.execute("SELECT * FROM usage_logs ORDER BY id ASC").fetchall()]
    conn.close()
    return {
        "status": "ok",
        "users": users,
        "conversations": conversations,
        "messages": messages,
        "usage_logs": usage_logs,
    }

