#!/usr/bin/env python3
"""
PDF Text Extractor
Extrae texto de archivos PDF para análisis.

Uso:
    python extract_pdf_text.py documento.pdf
    python extract_pdf_text.py documento.pdf --output salida.txt
"""

import argparse
import sys
from pathlib import Path


def extract_text_pypdf2(pdf_path: str) -> str:
    """Extrae texto usando PyPDF2."""
    try:
        from PyPDF2 import PdfReader  # pyre-ignore[21]: optional runtime dep
    except ImportError:
        print("Error: Instala PyPDF2 con 'pip install PyPDF2'")
        sys.exit(1)

    reader = PdfReader(pdf_path)
    text_parts = []

    for i, page in enumerate(reader.pages):
        page_text = page.extract_text()
        if page_text:
            text_parts.append(f"--- Página {i+1} ---\n{page_text}")

    return '\n\n'.join(text_parts)

def extract_text_pdfplumber(pdf_path: str) -> str:
    """Extrae texto usando pdfplumber (mejor para tablas)."""
    try:
        import pdfplumber  # pyre-ignore[21]: optional runtime dep
    except ImportError:
        print("pdfplumber no disponible, usando PyPDF2...")
        return extract_text_pypdf2(pdf_path)

    text_parts = []

    with pdfplumber.open(pdf_path) as pdf:
        for i, page in enumerate(pdf.pages):
            page_text = page.extract_text()
            if page_text:
                text_parts.append(f"--- Página {i+1} ---\n{page_text}")

    return '\n\n'.join(text_parts)

def main():
    parser = argparse.ArgumentParser(description='Extraer texto de PDF')
    parser.add_argument('pdf_path', help='Ruta al archivo PDF')
    parser.add_argument('-o', '--output', help='Archivo de salida (opcional)')
    parser.add_argument('--engine', choices=['pypdf2', 'pdfplumber'],
                        default='pypdf2', help='Motor de extracción')

    args = parser.parse_args()

    pdf_path = Path(args.pdf_path)
    if not pdf_path.exists():
        print(f"Error: No se encuentra el archivo {pdf_path}")
        sys.exit(1)

    print(f"Extrayendo texto de: {pdf_path}")
    print(f"Motor: {args.engine}")

    if args.engine == 'pdfplumber':
        text = extract_text_pdfplumber(str(pdf_path))
    else:
        text = extract_text_pypdf2(str(pdf_path))

    if args.output:
        output_path = Path(args.output)
        output_path.write_text(text, encoding='utf-8')
        print(f"Texto guardado en: {output_path}")
        print(f"Total: {len(text)} caracteres, {text.count(chr(10))} líneas")
    else:
        print("\n" + "="*60)
        print(text)
        print("="*60)
        print(f"\nTotal: {len(text)} caracteres")

if __name__ == '__main__':
    main()
