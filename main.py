from fastapi import FastAPI, File, UploadFile, Form
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import os
from typing import Optional
from dotenv import load_dotenv
from google import genai
import cv2
import pytesseract
import numpy as np

pytesseract.pytesseract.tesseract_cmd = r"C:\Program Files\Tesseract-OCR\tesseract.exe"

# Load environment variables
load_dotenv()

# Initialize Gemini Client
client = genai.Client(api_key=os.getenv("GEMINI_API_KEY"))

app = FastAPI(title="SnipTutor API")

# Enable CORS for local development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class TextInput(BaseModel):
    text: str

def call_gemini(prompt: str) -> str:
    models_to_try = ["gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.5-flash"]
    last_error = None
    for model_name in models_to_try:
        try:
            response = client.models.generate_content(
                model=model_name,
                contents=prompt
            )
            if response and response.text:
                return response.text
        except Exception as e:
            last_error = e
            continue
    raise last_error if last_error else RuntimeError("Failed to generate response from Gemini API")

@app.get("/")
def read_root():
    return {"message": "Hello, SnipTutor is running with Gemini!"}

@app.post("/process-text/")
def process_text(input: TextInput):
    user_text = input.text
    try:
        response_text = call_gemini(user_text)
        return {"input": user_text, "gemini_response": response_text}
    except Exception as e:
        return {"error": str(e)}

@app.post("/process-image/")
async def process_image(
    file: UploadFile = File(...),
    prompt: Optional[str] = Form(None),
    mode: Optional[str] = Form("Student")
):
    try:
        contents = await file.read()
        nparr = np.frombuffer(contents, np.uint8)
        img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        # 1. OCR to extract text from the screenshot
        extracted_text = pytesseract.image_to_string(img).strip()

        # 2. Build contextual prompt combining screenshot text + user question + mode
        user_question = prompt.strip() if (prompt and prompt.strip()) else "Please analyze and explain what is in this screenshot."
        
        mode_instruction = (
            "Explain in simple, beginner-friendly terms with clear intuition."
            if mode == "Student"
            else "Provide technical, concise, developer-focused solutions with code snippets."
        )

        full_prompt = f"""You are SnipTutor AI Assistant operating in {mode} Mode.
Style instruction: {mode_instruction}

--- SCREENSHOT CONTENT (OCR Extracted) ---
{extracted_text if extracted_text else "[No readable text found in image - analyze visual structure if applicable]"}

--- USER QUESTION ---
{user_question}

Please answer the user's question directly and accurately based on the screenshot provided above."""

        response_text = call_gemini(full_prompt)

        return {
            "extracted_text": extracted_text,
            "user_question": user_question,
            "gemini_response": response_text
        }
    except Exception as e:
        return {"error": str(e)}
