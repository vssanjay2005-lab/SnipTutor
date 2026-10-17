from fastapi import FastAPI, File, UploadFile, Form, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel
import os
import io
import shutil
import secrets
import sqlite3
import hashlib
import urllib.request
from typing import Optional, List
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

def get_gemini_client():
    api_key = os.getenv("GEMINI_API_KEY")
    if not api_key:
        raise RuntimeError("GEMINI_API_KEY is not set. Add it to your .env file or cloud environment variables.")
    return genai.Client(api_key=api_key)

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

def call_gemini(prompt: str, image_bytes: Optional[bytes] = None, mime_type: str = "image/png") -> str:
    gemini_client = get_gemini_client()
    models_to_try = [
        "gemini-3.8-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-flash-latest",
    ]
    contents_payload = (
        [types.Part.from_bytes(data=image_bytes, mime_type=mime_type), prompt]
        if image_bytes
        else prompt
    )
    last_error = None
    for model_name in models_to_try:
        try:
            response = gemini_client.models.generate_content(
                model=model_name,
                contents=contents_payload
            )
            if response and response.text:
                return response.text
        except Exception as e:
            last_error = e
            continue

    # If multimodal image payload failed across models, fall back to prompt + OCR text
    if image_bytes is not None:
        for model_name in models_to_try:
            try:
                response = gemini_client.models.generate_content(
                    model=model_name,
                    contents=prompt
                )
                if response and response.text:
                    return response.text
            except Exception as e:
                last_error = e
                continue

    raise last_error if last_error else RuntimeError("Failed to generate response from Gemini API")

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

@app.post("/process-text/")
def process_text(input: TextInput):
    try:
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

        response_text = call_gemini(context_prompt)
        return {"input": input.text, "gemini_response": response_text}
    except Exception as e:
        return {"error": str(e)}

@app.post("/process-image/")
async def process_image(
    file: UploadFile = File(...),
    prompt: Optional[str] = Form(None),
    mode: Optional[str] = Form("Student"),
    history: Optional[str] = Form(None)
):
    try:
        contents = await file.read()
        extracted_text = ""
        optimized_bytes = contents
        mime_type = file.content_type if (file.content_type and file.content_type.startswith("image/")) else "image/png"

        # Fast preprocessing: resize if large to speed up both Tesseract OCR & Gemini upload
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
                success, encoded_jpg = cv2.imencode(".jpg", resized, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
                if success:
                    optimized_bytes = encoded_jpg.tobytes()
                    mime_type = "image/jpeg"
                gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
                extracted_text = pytesseract.image_to_string(gray, config='--oem 3 --psm 6').strip()
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

--- SCREENSHOT OCR SUPPLEMENT ---
{extracted_text if extracted_text else "[Analyze the attached screenshot image directly using your vision capabilities]"}

--- USER QUESTION ABOUT SCREENSHOT ---
{user_question}

Please provide your comprehensive SnipTutor response:"""

        response_text = call_gemini(full_prompt, image_bytes=optimized_bytes, mime_type=mime_type)

        return {
            "extracted_text": extracted_text,
            "user_question": user_question,
            "gemini_response": response_text
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
    file: UploadFile = File(...),
    prompt: Optional[str] = Form(None),
    mode: Optional[str] = Form("Student"),
    history: Optional[str] = Form(None)
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
            response_text = call_gemini(full_prompt, image_bytes=file_bytes, mime_type=mime_map.get(ext, "image/png"))
        else:
            response_text = call_gemini(full_prompt)

        return {
            "filename": filename,
            "extracted_length": len(extracted_content),
            "gemini_response": response_text
        }
    except Exception as e:
        return {"error": str(e)}

class UrlRequest(BaseModel):
    url: str
    prompt: Optional[str] = None
    mode: Optional[str] = "Student"
    history: Optional[str] = None

@app.post("/process-url/")
def process_url(req: UrlRequest):
    try:
        url = req.url.strip()
        if not url.startswith("http://") and not url.startswith("https://"):
            url = "https://" + url

        request = urllib.request.Request(
            url,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
        )
        with urllib.request.urlopen(request, timeout=12) as response:
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

        response_text = call_gemini(full_prompt)
        return {
            "url": url,
            "title": page_title,
            "gemini_response": response_text
        }
    except Exception as e:
        return {"error": f"Failed to fetch and analyze URL: {str(e)}"}

# ==============================================================================
# DATABASE SETUP & USER AUTHENTICATION / CONVERSATION PERSISTENCE
# ==============================================================================
DB_PATH = os.getenv("SNIPTUTOR_DB_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "sniptutor.db"))

def get_db_connection():
    conn = sqlite3.connect(DB_PATH, timeout=15.0)
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA foreign_keys=ON;")
    return conn

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
    conn.commit()
    conn.close()

init_db()

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
