#!/usr/bin/env python3
"""
Gera os icones do SPOOK SERVER a partir da arte original.

A arte ("Servidor Neon em Espiral Energetica.png") tem fundo preto chapado
nas quatro bordas, entao a estrategia e a mesma de icone adaptativo profissional:

  background  -> cor chapada da propria arte (as bordas), 108dp
  foreground  -> a arte escalada para 72dp (a safe zone) e centralizada
  monochrome  -> a arte em alfa, para o icone tematico do Android 13+

Assim o lancador aplica a mascara (circulo/quadrado arredondado) sem cortar
o spiral: ele inteiro cai dentro dos 72dp centrais.

Uso:  python3 tools/gerar_icones.py "caminho/da/arte.png"
"""

import os
import sys
from collections import Counter

from PIL import Image, ImageDraw, ImageFilter

# 108dp = camada adaptativa inteira; 72dp = safe zone (66,67%)
ADAPTATIVO_DP = {108: 1, 162: 1.5, 216: 2, 324: 3, 432: 4}
# tamanho do icone legado em px por densidade
LEGADO_PX = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

RAIO_SAFE = 72 / 108


def cor_de_fundo(im: Image.Image) -> tuple:
    """Mediana das bordas: a arte ja traz o fundo, entao repetimos essa cor."""
    w, h = im.size
    amostras = []
    passo = max(1, w // 60)
    for x in range(0, w, passo):
        amostras += [im.getpixel((x, 0)), im.getpixel((x, h - 1))]
    for y in range(0, h, passo):
        amostras += [im.getpixel((0, y)), im.getpixel((w - 1, y))]
    canais = []
    for i in range(3):
        valores = sorted(a[i] for a in amostras)
        canais.append(valores[len(valores) // 2])
    return tuple(canais)


def recorte_seguro(im: Image.Image) -> Image.Image:
    """
    A arte inteira, mas reduzida para caber na safe zone circular.
    Como a safe zone e um circulo inscrito, usamos o diametro util 72/108.
    """
    w, h = im.size
    lado = int(max(w, h) * RAIO_SAFE)
    arte = im.resize((lado, lado), Image.LANCZOS)
    return arte


def mascara_arredondada(tam: int, circulo: bool, superamostra: int = 4) -> Image.Image:
    """Mascara antisserrilhada (superamostra + LANCZOS)."""
    grande = tam * superamostra
    m = Image.new("L", (grande, grande), 0)
    d = ImageDraw.Draw(m)
    if circulo:
        d.ellipse((0, 0, grande - 1, grande - 1), fill=255)
    else:
        # "squircle" simplificado: retangulo com cantos bem arredondados
        r = int(grande * 0.22)
        d.rounded_rectangle((0, 0, grande - 1, grande - 1), radius=r, fill=255)
    return m.resize((tam, tam), Image.LANCZOS)


def camada_adaptativa(arte: Image.Image, fundo, tam: int) -> Image.Image:
    """
    72dp de arte no meio de um canvas transparente de 108dp.
    Fora da arte fica transparente, e quem preenche e a camada `background`
    (a cor chapada da propria arte, #000009) -- os dois se emendam sem costura.
    """
    canvas = Image.new("RGBA", (tam, tam), (0, 0, 0, 0))
    lado = int(tam * RAIO_SAFE)
    mini = recorte_seguro(arte).resize((lado, lado), Image.LANCZOS).convert("RGBA")
    pos = (tam - lado) // 2
    canvas.paste(mini, (pos, pos), mini)
    return canvas


def monocromatica(arte: Image.Image, tam: int) -> Image.Image:
    """Alfa a partir do brilho: o Android 13+ pinta o icone com a cor do tema."""
    cinza = arte.convert("L")
    alfa = cinza.point(lambda v: 0 if v < 40 else min(255, int((v - 40) * 1.6)))
    out = Image.merge("RGBA", (
        Image.new("L", cinza.size, 255),
        Image.new("L", cinza.size, 255),
        Image.new("L", cinza.size, 255),
        alfa,
    ))
    lado = int(tam * RAIO_SAFE)
    out = out.resize((lado, lado), Image.LANCZOS)
    canvas = Image.new("RGBA", (tam, tam), (0, 0, 0, 0))
    canvas.paste(out, ((tam - lado) // 2,) * 2, out)
    return canvas


def principal(arte: Image.Image, fundo, tam: int) -> Image.Image:
    """Icone legado (pre-API 26): arte+mascara, ja arredondado no aparelho."""
    canvas = Image.new("RGBA", (tam, tam), fundo + (255,))
    lado = int(tam * 0.92)
    mini = recorte_seguro(arte).resize((lado, lado), Image.LANCZOS).convert("RGBA")
    pos = (tam - lado) // 2
    canvas.paste(mini, (pos, pos), mini)
    return canvas


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    origem = sys.argv[1]
    raiz = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(raiz, "app", "src", "main", "res")

    arte = Image.open(origem).convert("RGB")
    fundo = cor_de_fundo(arte)
    print(f"arte: {arte.size[0]}x{arte.size[1]}  fundo medido: rgb{fundo}")

    # ---- camadas adaptativas
    for tam in ADAPTATIVO_DP:
        dens = {108: "mdpi", 162: "hdpi", 216: "xhdpi", 324: "xxhdpi", 432: "xxxhdpi"}[tam]
        pasta = os.path.join(res, f"drawable-{dens}")
        os.makedirs(pasta, exist_ok=True)
        camada_adaptativa(arte, fundo, tam).save(
            os.path.join(pasta, "ic_launcher_foreground.png"))
        monocromatica(arte, tam).save(
            os.path.join(pasta, "ic_launcher_monochrome.png"))
        print(f"  {dens:<8} fg+mono {tam}x{tam}")

    # ---- icones legados, ja mascarados (redondo e squircle)
    for dens, tam in LEGADO_PX.items():
        pasta = os.path.join(res, f"mipmap-{dens}")
        os.makedirs(pasta, exist_ok=True)
        base = principal(arte, fundo, tam)
        base.putalpha(mascara_arredondada(tam, circulo=False))
        base.save(os.path.join(pasta, "ic_launcher.png"))
        base.putalpha(mascara_arredondada(tam, circulo=True))
        base.save(os.path.join(pasta, "ic_launcher_round.png"))
        print(f"  {dens:<8} legado {tam}x{tam}")

    # ---- cor do fundo, como recurso (o XML adaptativo aponta pra ele)
    hexcor = f"#{fundo[0]:02X}{fundo[1]:02X}{fundo[2]:02X}"
    valores = os.path.join(res, "values")
    os.makedirs(valores, exist_ok=True)
    with open(os.path.join(valores, "ic_launcher_background.xml"), "w",
              encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                "<resources>\n"
                f'    <color name="ic_launcher_background">{hexcor}</color>\n'
                "</resources>\n")
    print(f"cor do background: {hexcor} (app/src/main/res/values)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
