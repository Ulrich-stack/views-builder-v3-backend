"""Génère src/main/resources/ai/section-templates.json. Usage : python3 tools/build_section_templates.py src/main/resources/ai/section-templates.json"""
import json, sys

# ---------- Briques de style partagées ----------
MUTED_LINE = "1px solid color-mix(in srgb, var(--text) 14%, transparent)"

def section(inner, padding="96px 6%", extra=None):
    st = {"width": "100%", "padding": padding, "background-color": "{{_bg}}", "color": "var(--text)",
          "font-family": "var(--font-family)", "display": "flex", "flex-direction": "column", "align-items": "center"}
    st.update(extra or {})
    return {"type": "container", "element": "section", "name": "{{_name}}", "styles": st, "children": [inner]}

def box(name, styles, children, **kw):
    node = {"type": "container", "element": "div", "name": name, "styles": styles, "children": children}
    node.update(kw)
    return node

def inner(name, styles, children, max_width="1120px"):
    st = {"width": "100%", "max-width": max_width}
    st.update(styles)
    return box(name, st, children)

def col(name, gap="24px", extra=None, children=(), **kw):
    st = {"display": "flex", "flex-direction": "column", "gap": gap}
    st.update(extra or {})
    return box(name, st, list(children), **kw)

def row(name, gap="16px", extra=None, children=(), **kw):
    st = {"display": "flex", "flex-wrap": "wrap", "align-items": "center", "gap": gap}
    st.update(extra or {})
    return box(name, st, list(children), **kw)

def grid(name, min_col="340px", gap="56px", extra=None, children=(), **kw):
    st = {"display": "grid", "grid-template-columns": f"repeat(auto-fit, minmax(min(100%, {min_col}), 1fr))",
          "gap": gap, "align-items": "start"}
    st.update(extra or {})
    return box(name, st, list(children), **kw)

def text(element, name, content, styles, **kw):
    node = {"type": "text", "element": element, "name": name, "content": content, "styles": styles}
    node.update(kw)
    return node

def h1(content="{{title}}", extra=None):
    st = {"font-family": "var(--font-family-heading)", "font-size": "calc(var(--font-size-xl) * 1.35)",
          "font-weight": "700", "line-height": "1.06", "letter-spacing": "-0.025em", "text-wrap": "balance"}
    st.update(extra or {})
    return text("h1", "Titre principal", content, st)

def h2(content="{{title}}", extra=None, **kw):
    st = {"font-family": "var(--font-family-heading)", "font-size": "var(--font-size-xl)", "font-weight": "700",
          "line-height": "1.12", "letter-spacing": "-0.02em", "text-wrap": "balance"}
    st.update(extra or {})
    return text("h2", "Titre", content, st, **kw)

def h3(content, name="Sous-titre", extra=None, **kw):
    st = {"font-family": "var(--font-family-heading)", "font-size": "calc(var(--font-size-base) * 1.25)",
          "font-weight": "700", "line-height": "1.3"}
    st.update(extra or {})
    return text("h3", name, content, st, **kw)

def lead(content="{{text}}", name="Introduction", extra=None, **kw):
    st = {"font-size": "calc(var(--font-size-base) * 1.2)", "line-height": "1.6", "color": "var(--text-muted)",
          "max-width": "58ch"}
    st.update(extra or {})
    return text("p", name, content, st, **kw)

def body(content, name="Texte", extra=None, **kw):
    st = {"font-size": "var(--font-size-base)", "line-height": "1.65", "color": "var(--text-muted)", "max-width": "65ch"}
    st.update(extra or {})
    return text("p", name, content, st, **kw)

def small(content, name="Précision", extra=None, **kw):
    st = {"font-size": "calc(var(--font-size-base) * 0.875)", "line-height": "1.5", "color": "var(--text-muted)"}
    st.update(extra or {})
    return text("span", name, content, st, **kw)

def button(content="{{cta}}", name="Bouton principal", inverted=False, extra=None, **kw):
    st = {"background-color": "var(--on-primary)" if inverted else "var(--primary)",
          "color": "var(--primary)" if inverted else "var(--on-primary)",
          "padding": "14px 26px", "border": "none", "border-radius": "var(--radius-main)",
          "font-family": "var(--font-family)", "font-size": "var(--font-size-base)", "font-weight": "600",
          "white-space": "nowrap", "display": "inline-block", "align-self": "flex-start"}
    st.update(extra or {})
    node = {"type": "action", "element": "button", "name": name, "content": content, "styles": st}
    node.update(kw)
    return node

def link(content, name="Lien", extra=None, **kw):
    st = {"color": "var(--text)", "font-weight": "600", "text-decoration": "underline",
          "text-underline-offset": "6px", "white-space": "nowrap"}
    st.update(extra or {})
    return text("a", name, content, st, config={"href": ""}, **kw)

def image(ratio="4 / 5", name="Photo", query="{{imageQuery}}", alt="{{imageAlt}}", extra=None, **kw):
    st = {"width": "100%", "aspect-ratio": ratio, "border-radius": "var(--radius-main)", "overflow": "hidden",
          "padding": "0", "background-color": "{{_card}}"}
    if ratio is None:
        del st["aspect-ratio"]  # la photo remplit la case de grille qui la porte
    st.update(extra or {})
    node = {"type": "media", "element": "img", "name": name, "styles": st,
            "config": {"imageQuery": query, "alt": alt}}
    node.update(kw)
    return node

def actions():
    return row("Actions", gap="12px 24px", extra={"padding-top": "8px"}, children=[
        button(),
        link("{{secondaryCta}}", "Lien secondaire", extra={"padding": "14px 4px"}, **{"if": "secondaryCta"}),
    ])

# ---------- Catalogue ----------
T = []

LABELS = {"nav": "Navigation", "hero-split": "Bandeau d'accueil", "hero-wide": "Bandeau d'accueil", "hero-poster": "Affiche",
          "features": "Points forts", "steps": "Étapes", "text-image": "Texte et photo", "image-text": "Photo et texte",
          "gallery": "Galerie", "price-list": "Carte des prix", "pricing": "Formules", "testimonial": "Témoignage",
          "faq": "Questions fréquentes", "contact": "Infos pratiques", "form": "Formulaire", "cta": "Appel à l'action",
          "hero-cover": "Bandeau photo", "hero-statement": "Bandeau manifeste", "hero-mosaic": "Bandeau mosaïque",
          "features-icons": "Points forts illustrés", "features-statement": "Points forts manifeste",
          "features-bento": "Points forts en blocs", "gallery-mosaic": "Galerie mosaïque",
          "gallery-editorial": "Galerie magazine", "cta-cover": "Appel à l'action photo",
          "cta-statement": "Appel à l'action manifeste",
          "footer": "Pied de page"}

# Familles de styles : chaque thème en privilégie une (ai/theme-briefs.json, « family »)
FAMILIES = {"editorial", "audacieux", "expressif", "chaleureux"}

def template(id, description, slots, widget, tone=True, families=()):
    assert set(families) <= FAMILIES, families
    node = {"id": id, "label": LABELS[id], "description": description, "slots": slots, "widget": widget, "tone": tone}
    if families:
        node["families"] = list(families)
    T.append(node)

template("nav", "Barre de navigation : nom de la marque, 2 à 4 liens et un bouton. Toujours en premier sur une page complète.",
    {"brand": "nom de la marque", "links": ["libellé de lien (2 à 4)"], "cta?": "bouton d'action court"},
    section(inner("Contenu de la barre", {"display": "flex", "flex-wrap": "wrap", "align-items": "center",
                                          "justify-content": "space-between", "gap": "16px 32px"}, [
        text("span", "Marque", "{{brand}}", {"font-family": "var(--font-family-heading)",
             "font-size": "calc(var(--font-size-base) * 1.3)", "font-weight": "700", "letter-spacing": "-0.01em"}),
        row("Liens", gap="12px 28px", children=[
            row("Liste des liens", gap="12px 28px", children=[
                text("a", "Lien", "{{value}}", {"color": "var(--text-muted)", "font-weight": "500", "white-space": "nowrap"},
                     config={"href": ""})], repeat="links"),
            button(extra={"padding": "10px 20px"}, **{"if": "cta"}),
        ]),
    ]), padding="20px 6%", extra={"border-bottom": MUTED_LINE}), tone=False)

template("hero-split", "Bandeau d'accueil : grand titre et texte à gauche, grande photo à droite. Le plus polyvalent.",
    {"title": "titre principal, 4 à 9 mots, concret", "text": "1 à 2 phrases", "cta": "bouton principal",
     "secondaryCta?": "lien secondaire", "note?": "précision courte sous les boutons (horaires, garantie...)",
     "imageQuery": "mots-clés anglais de la photo", "imageAlt": "description de la photo"},
    section(grid("Composition", min_col="300px", gap="40px 48px", extra={"width": "100%", "max-width": "1120px", "align-items": "center"}, children=[
        col("Texte", gap="24px", children=[h1(), lead(), actions(), small("{{note}}", **{"if": "note"})]),
        image("4 / 5", "Photo principale"),
    ]), padding="88px 6%"))

template("hero-wide", "Bandeau d'accueil éditorial : titre très grand aligné à gauche, puis une photo panoramique pleine largeur.",
    {"title": "titre principal, 4 à 9 mots", "text": "1 à 2 phrases", "cta": "bouton principal",
     "secondaryCta?": "lien secondaire", "imageQuery": "mots-clés anglais", "imageAlt": "description"},
    section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "48px"}, [
        grid("Titre et texte", min_col="320px", gap="24px 56px", extra={"align-items": "end"}, children=[
            h1(extra={"font-size": "calc(var(--font-size-xl) * 1.6)"}),
            col("Accroche", gap="20px", children=[lead(), actions()]),
        ]),
        image("16 / 7", "Photo panoramique"),
    ]), padding="88px 6% 96px"))

template("hero-poster", "Bandeau affiche sans photo, sur la couleur principale du thème : pour un message fort ou une offre.",
    {"title": "titre très court et percutant", "text": "1 phrase", "cta": "bouton principal"},
    section(inner("Affiche", {"display": "flex", "flex-direction": "column", "gap": "28px"}, [
        h1(extra={"font-size": "calc(var(--font-size-xl) * 1.7)", "color": "var(--on-primary)", "max-width": "16ch"}),
        lead(extra={"color": "var(--on-primary)"}),
        button(inverted=True),
    ]), padding="120px 6%", extra={"background-color": "var(--primary)", "color": "var(--on-primary)"}), tone=False,
    families=["expressif"])

template("features", "Points forts sans cartes : titre à gauche, liste de 3 à 5 arguments séparés par des filets à droite.",
    {"title": "titre de section", "intro?": "1 phrase", "items": [{"title": "argument court", "text": "1 à 2 phrases"}]},
    section(grid("Composition", min_col="300px", gap="40px 72px", extra={"width": "100%", "max-width": "1120px"}, children=[
        col("En-tête", gap="16px", children=[h2(), lead("{{intro}}", **{"if": "intro"})]),
        col("Liste des arguments", gap="0px", repeat="items", children=[
            col("Argument", gap="8px", extra={"border-top": MUTED_LINE, "padding": "24px 0"}, children=[
                h3("{{title}}", "Argument"), body("{{text}}")]),
        ]),
    ])), families=["editorial"])

template("steps", "Étapes numérotées (3 à 4) : quand l'ordre compte (réservation, déroulé d'un cours, commande).",
    {"title": "titre de section", "intro?": "1 phrase", "items": [{"title": "étape", "text": "1 phrase"}]},
    section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "48px"}, [
        col("En-tête", gap="16px", children=[h2(), lead("{{intro}}", **{"if": "intro"})]),
        grid("Étapes", min_col="200px", gap="32px", repeat="items", children=[
            col("Étape", gap="12px", children=[
                text("span", "Numéro", "{{_index}}", {"font-family": "var(--font-family-heading)",
                     "font-size": "var(--font-size-xl)", "font-weight": "700", "color": "var(--primary)", "line-height": "1"}),
                h3("{{title}}", "Étape"), body("{{text}}")]),
        ]),
    ])))

for id_, desc, image_first in [
        ("text-image", "Texte à gauche, photo à droite : présenter le lieu, l'histoire, une offre.", False),
        ("image-text", "Photo à gauche, texte à droite : même usage que text-image, pour alterner.", True)]:
    texte = col("Texte", gap="20px", extra={"align-self": "center"}, children=[
        h2(), body("{{text}}", extra={"font-size": "calc(var(--font-size-base) * 1.1)"}),
        col("Points clés", gap="0px", repeat="points", children=[
            text("p", "Point clé", "{{value}}", {"font-weight": "600", "padding": "14px 0", "border-top": MUTED_LINE})]),
        button(**{"if": "cta"}),
    ])
    photo = image("4 / 3", "Photo")
    template(id_, desc,
        {"title": "titre de section", "text": "2 à 3 phrases", "points?": ["point clé court (2 à 4)"], "cta?": "bouton",
         "imageQuery": "mots-clés anglais", "imageAlt": "description"},
        section(grid("Composition", min_col="300px", gap="40px 56px",
                     extra={"width": "100%", "max-width": "1120px", "align-items": "center"},
                     children=[photo, texte] if image_first else [texte, photo])))

template("gallery", "Galerie de 3 à 6 photos avec un titre : ambiance, réalisations, produits.",
    {"title": "titre de section", "intro?": "1 phrase", "images": [{"imageQuery": "mots-clés anglais", "alt": "description"}]},
    section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "40px"}, [
        col("En-tête", gap="16px", children=[h2(), lead("{{intro}}", **{"if": "intro"})]),
        grid("Photos", min_col="240px", gap="16px", repeat="images", children=[
            image("1 / 1", "Photo de galerie", query="{{imageQuery}}", alt="{{alt}}")]),
    ])))

template("price-list", "Carte ou liste de prix façon menu (nom, description, prix) : restaurant, salon, services à l'unité.",
    {"title": "titre de section", "intro?": "1 phrase", "items": [{"name": "nom", "description?": "courte description", "price": "prix, ex : 6,50 €"}],
     "note?": "précision sous la liste"},
    section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "32px"}, [
        col("En-tête", gap="16px", children=[h2(), lead("{{intro}}", **{"if": "intro"})]),
        col("Liste des prix", gap="0px", repeat="items", children=[
            box("Ligne de prix", {"display": "grid", "grid-template-columns": "1fr auto", "gap": "4px 24px",
                                  "padding": "20px 0", "border-bottom": MUTED_LINE, "align-items": "baseline"}, [
                text("span", "Nom", "{{name}}", {"font-weight": "600", "font-size": "calc(var(--font-size-base) * 1.1)"}),
                text("span", "Prix", "{{price}}", {"font-weight": "700", "color": "var(--primary)",
                     "font-variant-numeric": "tabular-nums", "white-space": "nowrap"}),
                body("{{description}}", "Description", extra={"grid-column": "1 / -1"}, **{"if": "description"}),
            ])]),
        small("{{note}}", **{"if": "note"}),
    ], max_width="760px")))

template("pricing", "Formules ou abonnements (2 à 3) côte à côte, avec prix et bouton.",
    {"title": "titre de section", "intro?": "1 phrase",
     "plans": [{"name": "nom de la formule", "price": "prix", "period?": "ex : / mois", "description": "pour qui, en 1 phrase",
                "features": ["avantage court (3 à 4)"], "cta": "bouton"}]},
    section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "48px"}, [
        col("En-tête", gap="16px", children=[h2(), lead("{{intro}}", **{"if": "intro"})]),
        grid("Formules", min_col="210px", gap="20px", extra={"align-items": "stretch"}, repeat="plans", children=[
            col("Formule", gap="16px", extra={"background-color": "{{_card}}", "padding": "28px 24px",
                                              "border-radius": "var(--radius-main)", "border": MUTED_LINE}, children=[
                h3("{{name}}", "Nom de la formule"),
                row("Prix", gap="6px", extra={"align-items": "baseline"}, children=[
                    text("span", "Montant", "{{price}}", {"font-family": "var(--font-family-heading)",
                         "font-size": "var(--font-size-xl)", "font-weight": "700", "line-height": "1"}),
                    small("{{period}}", "Période", **{"if": "period"})]),
                body("{{description}}"),
                col("Avantages", gap="8px", extra={"border-top": MUTED_LINE, "padding-top": "16px", "flex": "1"}, repeat="features", children=[
                    body("{{value}}", "Avantage", extra={"color": "var(--text)", "font-size": "calc(var(--font-size-base) * 0.95)"})]),
                button(extra={"align-self": "stretch", "text-align": "center"}),
            ])]),
    ])))

template("testimonial", "Une grande citation de client, mise en avant. Une seule par page.",
    {"quote": "citation de 1 à 3 phrases, crédible et précise", "author": "prénom et nom", "role?": "contexte (cliente depuis 2021...)"},
    section(inner("Citation", {"display": "flex", "flex-direction": "column", "gap": "28px"}, [
        text("blockquote", "Citation", "« {{quote}} »", {"font-family": "var(--font-family-heading)",
             "font-size": "calc(var(--font-size-xl) * 0.85)", "line-height": "1.3", "letter-spacing": "-0.01em",
             "text-wrap": "balance"}),
        col("Auteur", gap="4px", children=[
            text("span", "Nom", "{{author}}", {"font-weight": "700"}),
            small("{{role}}", "Contexte", **{"if": "role"})]),
    ], max_width="860px")))

template("faq", "Questions fréquentes (3 à 6) : titre à gauche, questions et réponses à droite.",
    {"title": "titre de section", "intro?": "1 phrase", "items": [{"question": "question", "answer": "réponse en 1 à 3 phrases"}]},
    section(grid("Composition", min_col="300px", gap="40px 72px", extra={"width": "100%", "max-width": "1120px"}, children=[
        col("En-tête", gap="16px", children=[h2(), lead("{{intro}}", **{"if": "intro"})]),
        col("Questions", gap="0px", repeat="items", children=[
            col("Question", gap="10px", extra={"border-bottom": MUTED_LINE, "padding": "24px 0"}, children=[
                h3("{{question}}", "Question"), body("{{answer}}", "Réponse")])]),
    ])))

template("contact", "Infos pratiques : adresse, horaires, téléphone (lignes libellé / valeur) avec une photo du lieu.",
    {"title": "titre de section", "text?": "1 phrase", "details": [{"label": "ex : Adresse, Horaires, Téléphone", "value": "valeur"}],
     "cta?": "bouton", "imageQuery": "mots-clés anglais", "imageAlt": "description"},
    section(grid("Composition", min_col="300px", gap="40px 56px", extra={"width": "100%", "max-width": "1120px", "align-items": "center"}, children=[
        col("Informations", gap="24px", children=[
            h2(), lead("{{text}}", **{"if": "text"}),
            col("Détails", gap="0px", repeat="details", children=[
                box("Ligne", {"display": "grid", "grid-template-columns": "minmax(110px, 1fr) 2fr", "gap": "16px",
                              "padding": "16px 0", "border-top": MUTED_LINE}, [
                    text("span", "Libellé", "{{label}}", {"font-weight": "600"}),
                    text("span", "Valeur", "{{value}}", {"color": "var(--text-muted)", "line-height": "1.5"})])]),
            button(**{"if": "cta"}),
        ]),
        image("1 / 1", "Photo du lieu"),
    ])))

template("form", "Formulaire (contact, réservation, inscription) : texte d'accompagnement à gauche, formulaire à droite.",
    {"title": "titre de section", "text": "1 à 2 phrases", "note?": "réassurance courte (réponse sous 24 h...)",
     "fields": [{"label": "libellé", "placeholder": "exemple de saisie"}],
     "message?": {"label": "libellé du champ long", "placeholder": "exemple"},
     "checkboxes?": ["case à cocher"], "submit": "libellé du bouton d'envoi"},
    section(grid("Composition", min_col="300px", gap="40px 72px", extra={"width": "100%", "max-width": "1120px"}, children=[
        col("Texte", gap="20px", children=[h2(), lead(), small("{{note}}", **{"if": "note"})]),
        col("Formulaire", gap="20px", extra={"background-color": "{{_card}}", "padding": "32px",
                                              "border-radius": "var(--radius-main)", "border": MUTED_LINE}, children=[
            grid("Champs", min_col="200px", gap="20px", repeat="fields", children=[
                col("Champ", gap="8px", children=[
                    text("span", "Libellé", "{{label}}", {"font-weight": "600", "font-size": "calc(var(--font-size-base) * 0.95)"}),
                    {"type": "input", "element": "input", "name": "Saisie", "config": {"placeholder": "{{placeholder}}"},
                     "styles": {"padding": "12px 14px", "border": MUTED_LINE, "border-radius": "var(--radius-main)",
                                "background-color": "var(--bg-page)", "font-family": "var(--font-family)",
                                "font-size": "var(--font-size-base)", "color": "var(--text)"}}])]),
            col("Message", gap="8px", children=[
                text("span", "Libellé", "{{message.label}}", {"font-weight": "600", "font-size": "calc(var(--font-size-base) * 0.95)"}),
                {"type": "input", "element": "textarea", "name": "Message", "config": {"placeholder": "{{message.placeholder}}"},
                 "styles": {"padding": "12px 14px", "min-height": "120px", "border": MUTED_LINE, "border-radius": "var(--radius-main)",
                            "background-color": "var(--bg-page)", "font-family": "var(--font-family)",
                            "font-size": "var(--font-size-base)", "color": "var(--text)"}}], **{"if": "message"}),
            col("Cases à cocher", gap="10px", repeat="checkboxes", children=[
                {"type": "input", "element": "checkbox", "name": "Case à cocher", "config": {"label": "{{value}}"},
                 "styles": {"font-size": "calc(var(--font-size-base) * 0.95)", "color": "var(--text)"}}]),
            button("{{submit}}", "Bouton d'envoi", extra={"align-self": "stretch", "text-align": "center"}),
        ]),
    ])))

template("cta", "Appel à l'action final dans un grand encadré de la couleur principale.",
    {"title": "titre court", "text?": "1 phrase", "cta": "bouton"},
    section(box("Encadré", {"width": "100%", "max-width": "1120px", "background-color": "var(--primary)",
                            "color": "var(--on-primary)", "border-radius": "var(--radius-main)", "padding": "56px 6%",
                            "display": "flex", "flex-wrap": "wrap", "align-items": "center",
                            "justify-content": "space-between", "gap": "24px 48px"}, [
        col("Texte", gap="12px", extra={"flex": "1 1 320px"}, children=[
            h2(extra={"color": "var(--on-primary)"}),
            lead("{{text}}", extra={"color": "var(--on-primary)"}, **{"if": "text"})]),
        button(inverted=True, extra={"align-self": "center"}),
    ]), padding="64px 6%"), families=["expressif"])

template("footer", "Pied de page : marque, phrase, liens, coordonnées et mentions. Toujours en dernier sur une page complète.",
    {"brand": "nom de la marque", "tagline?": "phrase courte", "links?": ["lien"], "details?": ["ligne de coordonnées"],
     "legal": "mention de bas de page, ex : © 2026 Nom"},
    section(inner("Contenu du pied de page", {"display": "flex", "flex-direction": "column", "gap": "40px"}, [
        grid("Colonnes", min_col="200px", gap="32px", children=[
            col("Marque", gap="10px", children=[
                text("span", "Marque", "{{brand}}", {"font-family": "var(--font-family-heading)",
                     "font-size": "calc(var(--font-size-base) * 1.3)", "font-weight": "700"}),
                body("{{tagline}}", "Phrase", **{"if": "tagline"})]),
            col("Liens", gap="10px", repeat="links", children=[
                text("a", "Lien", "{{value}}", {"color": "var(--text-muted)"}, config={"href": ""})]),
            col("Coordonnées", gap="10px", repeat="details", children=[
                text("span", "Coordonnée", "{{value}}", {"color": "var(--text-muted)", "line-height": "1.5"})]),
        ]),
        small("{{legal}}", "Mentions", extra={"border-top": MUTED_LINE, "padding-top": "24px"}),
    ]), padding="64px 6% 40px", extra={"border-top": MUTED_LINE}))

# ---------- Variantes à forte personnalité ----------
# Les tailles de titre suivent la largeur de la section (unités cqi) : la section déclare « container-type ».
QUERY = {"container-type": "inline-size"}

def display(size_cqi, max_factor, min_factor=2.4):
    """Taille de titre fluide, bornée par la taille de base du thème."""
    return (f"clamp(calc(var(--font-size-base) * {min_factor}), {size_cqi}cqi, "
            f"calc(var(--font-size-base) * {max_factor}))")

# Texte posé sur une photo assombrie : blanc quel que soit le thème, la photo est sombre sous le voile
ON_PHOTO = "white"
ON_PHOTO_MUTED = "color-mix(in srgb, white 82%, transparent)"
PHOTO_LINE = "1px solid color-mix(in srgb, white 30%, transparent)"
SOFT_SHADOW = "0 28px 56px -24px color-mix(in srgb, black 45%, transparent)"

def veil(direction="to top", strong=72, light=18):
    return (f"linear-gradient({direction}, color-mix(in srgb, black {strong}%, transparent), "
            f"color-mix(in srgb, black {light}%, transparent))")

def photo_section(inner_node, query="{{imageQuery}}", padding="96px 6%", extra=None, veil_css=None):
    """Section pleine largeur sur une photo de fond (trouvée par le serveur) sous un voile sombre."""
    st = {"background-color": "color-mix(in srgb, var(--primary) 35%, black)",
          "background-image": veil_css or veil(), "background-size": "cover", "background-position": "center",
          "color": ON_PHOTO}
    st.update(QUERY)
    st.update(extra or {})
    node = section(inner_node, padding=padding, extra=st)
    node["config"] = {"backgroundQuery": query}
    return node

def icon(name="{{icon}}", size="28px", extra=None, **kw):
    st = {"width": size, "height": size, "flex-shrink": "0", "color": "var(--primary)"}
    st.update(extra or {})
    node = {"type": "media", "element": "icon", "name": "Icône", "config": {"icon": name}, "styles": st}
    node.update(kw)
    return node

def photo_link(content, name="Lien secondaire", **kw):
    return link(content, name, extra={"color": ON_PHOTO, "padding": "14px 4px",
                                      "text-decoration-color": "color-mix(in srgb, white 55%, transparent)"}, **kw)

# --- Bandeaux ---

template("hero-cover", "Bandeau plein écran sur une grande photo d'ambiance assombrie, titre très grand en bas à gauche. "
         "Pour un lieu, une ambiance, un savoir-faire à montrer d'emblée.",
    {"title": "titre principal, 3 à 7 mots", "text": "1 phrase", "cta": "bouton principal", "secondaryCta?": "lien secondaire",
     "details?": ["repère pratique très court (2 à 3), ex : Ouvert du mardi au dimanche"],
     "imageQuery": "mots-clés anglais d'une photo d'ambiance large (lieu, matière, geste)"},
    photo_section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "28px"}, [
        h1(extra={"font-size": display(8.5, 6), "line-height": "1", "letter-spacing": "-0.03em",
                  "max-width": "14ch", "color": ON_PHOTO}),
        lead(extra={"color": ON_PHOTO_MUTED, "max-width": "46ch"}),
        row("Actions", gap="12px 28px", extra={"padding-top": "4px"}, children=[
            button(), photo_link("{{secondaryCta}}", **{"if": "secondaryCta"})]),
        row("Repères", gap="8px 40px", extra={"border-top": PHOTO_LINE, "padding-top": "20px", "margin-top": "24px"},
            repeat="details", children=[small("{{value}}", "Repère", extra={"color": ON_PHOTO_MUTED})],
            **{"if": "details"}),
    ]), padding="64px 6% 56px", extra={"min-height": "680px", "justify-content": "flex-end"}),
    tone=False, families=["chaleureux", "editorial"])

template("hero-statement", "Bandeau manifeste : un titre géant sur toute la largeur, puis texte et boutons sous un filet, "
         "puis une grande photo panoramique. Pour une marque sûre d'elle, un message net.",
    {"title": "titre principal, 3 à 8 mots, affirmé", "text": "1 à 2 phrases", "cta": "bouton principal",
     "secondaryCta?": "lien secondaire", "imageQuery": "mots-clés anglais d'une photo panoramique (lieu, équipe, produit)",
     "imageAlt": "description"},
    section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "56px"}, [
        h1(extra={"font-size": display(10, 6, 2.6), "line-height": "0.98", "letter-spacing": "-0.04em",
                  "max-width": "16ch"}),
        grid("Accroche", min_col="300px", gap="24px 64px",
             extra={"border-top": "1px solid var(--text)", "padding-top": "28px", "align-items": "start"}, children=[
            lead(extra={"max-width": "44ch", "color": "var(--text)"}),
            row("Actions", gap="12px 28px", extra={"justify-content": "flex-end"}, children=[
                link("{{secondaryCta}}", "Lien secondaire", extra={"padding": "14px 4px"}, **{"if": "secondaryCta"}),
                button(extra={"align-self": "center"})]),
        ]),
        image("21 / 9", "Photo panoramique", **{"if": "imageQuery"}),
    ]), padding="120px 6% 96px", extra=QUERY), families=["audacieux"])

template("hero-mosaic", "Bandeau mosaïque : titre à gauche, à droite deux à trois photos qui se chevauchent "
         "autour d'un bloc de couleur portant une icône. Pour une marque vivante, créative, joyeuse.",
    {"title": "titre principal, 3 à 7 mots", "text": "1 à 2 phrases", "cta": "bouton principal",
     "secondaryCta?": "lien secondaire", "icon": "nom d'icône (liste plus bas)",
     "images": [{"imageQuery": "mots-clés anglais", "alt": "description"}]},
    section(grid("Composition", min_col="320px", gap="48px 56px",
                 extra={"width": "100%", "max-width": "1200px", "align-items": "center"}, children=[
        col("Texte", gap="24px", children=[
            h1(extra={"font-size": display(6.5, 5), "line-height": "1", "letter-spacing": "-0.035em"}),
            lead(), actions()]),
        box("Mosaïque", {"display": "grid", "grid-template-columns": "repeat(12, 1fr)",
                         "grid-template-rows": "repeat(8, clamp(36px, 5.6cqi, 64px))", "width": "100%"}, [
            box("Bloc de couleur", {"grid-column": "8 / 13", "grid-row": "1 / 4", "background-color": "var(--primary)",
                                    "border-radius": "var(--radius-main)", "display": "flex",
                                    "align-items": "center", "justify-content": "center"}, [
                icon(size="44px", extra={"color": "var(--on-primary)"})]),
            image(None, "Photo principale", query="{{images.0.imageQuery}}", alt="{{images.0.alt}}",
                  extra={"grid-column": "1 / 9", "grid-row": "2 / 8", "z-index": "1", 
                         "box-shadow": SOFT_SHADOW}),
            image(None, "Photo secondaire", query="{{images.1.imageQuery}}", alt="{{images.1.alt}}",
                  extra={"grid-column": "7 / 12", "grid-row": "5 / 9", "z-index": "2", 
                         "border": "8px solid {{_bg}}"}, **{"if": "images.1"}),
            image(None, "Petite photo", query="{{images.2.imageQuery}}", alt="{{images.2.alt}}",
                  extra={"grid-column": "10 / 13", "grid-row": "3 / 6", "z-index": "3", 
                         "border": "6px solid {{_bg}}"}, **{"if": "images.2"}),
        ]),
    ]), padding="88px 6%", extra=QUERY), families=["expressif"])

# --- Points forts ---

template("features-icons", "Points forts illustrés : une photo d'ambiance à gauche, à droite le titre et 3 à 4 arguments, "
         "chacun avec une icône fine. Rassurant et chaleureux.",
    {"title": "titre de section", "intro?": "1 phrase",
     "items": [{"icon": "nom d'icône (liste plus bas)", "title": "argument court", "text": "1 à 2 phrases"}],
     "imageQuery": "mots-clés anglais", "imageAlt": "description"},
    section(grid("Composition", min_col="320px", gap="48px 80px",
                 extra={"width": "100%", "max-width": "1120px", "align-items": "center"}, children=[
        image("4 / 5", "Photo d'ambiance"),
        col("Texte", gap="32px", children=[
            col("En-tête", gap="16px", children=[h2(), lead("{{intro}}", **{"if": "intro"})]),
            col("Liste des arguments", gap="28px", repeat="items", children=[
                box("Argument", {"display": "grid", "grid-template-columns": "56px 1fr", "gap": "4px 20px",
                                 "align-items": "start"}, [
                    box("Pastille", {"grid-row": "1 / 3", "width": "56px", "height": "56px", "border-radius": "999px",
                                     "background-color": "color-mix(in srgb, var(--primary) 12%, {{_bg}})",
                                     "display": "flex", "align-items": "center", "justify-content": "center"}, [
                        icon(size="28px")]),
                    h3("{{title}}", "Argument", extra={"padding-top": "4px"}),
                    body("{{text}}")]),
            ]),
        ]),
    ])), families=["chaleureux"])

template("features-statement", "Points forts manifeste : chaque argument sur une ligne pleine largeur, titre très grand "
         "à gauche, explication à droite, séparés par des filets nets. Sobre et affirmé.",
    {"title": "titre de section", "items": [{"title": "argument en 2 à 4 mots", "text": "1 à 2 phrases"}]},
    section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "40px"}, [
        h2(extra={"max-width": "20ch"}),
        col("Liste des arguments", gap="0px", repeat="items", children=[
            grid("Argument", min_col="280px", gap="12px 64px",
                 extra={"border-top": "1px solid var(--text)", "padding": "32px 0 40px", "align-items": "baseline"}, children=[
                text("h3", "Argument", "{{title}}", {"font-family": "var(--font-family-heading)",
                     "font-size": display(5, 3.4, 1.8), "font-weight": "700", "line-height": "1.05",
                     "letter-spacing": "-0.03em", "text-wrap": "balance"}),
                body("{{text}}", extra={"font-size": "calc(var(--font-size-base) * 1.1)", "max-width": "48ch"})]),
        ]),
    ]), extra=QUERY), families=["audacieux"])

template("features-bento", "Points forts en blocs : un grand bloc photo avec l'argument principal, à côté une phrase forte "
         "sur la couleur du thème et 2 à 3 blocs plus petits avec icône. Vivant, coloré.",
    {"title": "titre de section",
     "highlight": {"title": "argument principal, 3 à 6 mots", "text": "1 phrase", "imageQuery": "mots-clés anglais"},
     "punchline?": "phrase forte très courte (un chiffre, une promesse)",
     "items": [{"icon": "nom d'icône (liste plus bas)", "title": "argument court", "text": "1 phrase"}]},
    section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "40px"}, [
        h2(extra={"max-width": "22ch"}),
        grid("Blocs", min_col="300px", gap="16px", extra={"align-items": "stretch"}, children=[
            dict(photo_section(col("Texte du bloc", gap="10px", children=[
                    h3("{{highlight.title}}", "Argument principal", extra={"color": ON_PHOTO,
                       "font-size": display(3.6, 2.6, 1.6), "line-height": "1.1", "letter-spacing": "-0.02em"}),
                    body("{{highlight.text}}", extra={"color": ON_PHOTO_MUTED})]),
                 query="{{highlight.imageQuery}}", padding="32px",
                 extra={"min-height": "460px", "justify-content": "flex-end", "align-items": "stretch",
                        "border-radius": "var(--radius-main)", "background-image": veil(strong=78, light=0)}),
                 element="div", name="Bloc photo"),
            col("Colonne de blocs", gap="16px", children=[
                text("p", "Phrase forte", "{{punchline}}", {"background-color": "var(--primary)", "color": "var(--on-primary)",
                     "border-radius": "var(--radius-main)", "padding": "32px", "font-family": "var(--font-family-heading)",
                     "font-size": display(3.2, 2.2, 1.5), "font-weight": "700", "line-height": "1.1",
                     "letter-spacing": "-0.02em", "text-wrap": "balance"}, **{"if": "punchline"}),
                col("Petits blocs", gap="16px", repeat="items", children=[
                    box("Bloc", {"display": "grid", "grid-template-columns": "auto 1fr", "gap": "6px 16px",
                                 "background-color": "{{_card}}", "border-radius": "var(--radius-main)", "padding": "24px"}, [
                        icon(size="26px", extra={"grid-row": "1 / 3"}),
                        h3("{{title}}", "Argument"),
                        body("{{text}}")])]),
            ]),
        ]),
    ]), extra=QUERY), families=["expressif"])

# --- Galeries ---

template("gallery-mosaic", "Galerie mosaïque : 3 à 4 photos de tailles différentes qui se chevauchent, sous un titre. "
         "Pour une ambiance, des réalisations, avec du rythme.",
    {"title": "titre de section", "intro?": "1 phrase", "images": [{"imageQuery": "mots-clés anglais", "alt": "description"}]},
    section(inner("Composition", {"display": "flex", "flex-direction": "column", "gap": "48px"}, [
        grid("En-tête", min_col="300px", gap="16px 64px", extra={"align-items": "end"}, children=[
            h2(), lead("{{intro}}", **{"if": "intro"})]),
        box("Mosaïque", {"display": "grid", "grid-template-columns": "repeat(12, 1fr)",
                         "grid-template-rows": "repeat(7, clamp(40px, 7cqi, 96px))", "width": "100%"}, [
            image(None, "Grande photo", query="{{images.0.imageQuery}}", alt="{{images.0.alt}}",
                  extra={"grid-column": "1 / 8", "grid-row": "1 / 6", }),
            image(None, "Photo haute", query="{{images.1.imageQuery}}", alt="{{images.1.alt}}",
                  extra={"grid-column": "9 / 13", "grid-row": "1 / 4", }, **{"if": "images.1"}),
            image(None, "Photo en avant", query="{{images.2.imageQuery}}", alt="{{images.2.alt}}",
                  extra={"grid-column": "6 / 10", "grid-row": "4 / 8", "z-index": "2", 
                         "border": "10px solid {{_bg}}", "box-shadow": SOFT_SHADOW}, **{"if": "images.2"}),
            image(None, "Photo de côté", query="{{images.3.imageQuery}}", alt="{{images.3.alt}}",
                  extra={"grid-column": "10 / 13", "grid-row": "4 / 7", }, **{"if": "images.3"}),
        ]),
    ]), extra=QUERY), families=["expressif", "audacieux"])

def captioned(index, ratio, name, extra=None):
    return col(name, gap="12px", extra=extra, children=[
        image(ratio, "Photo", query=f"{{{{images.{index}.imageQuery}}}}", alt=f"{{{{images.{index}.alt}}}}"),
        small(f"{{{{images.{index}.caption}}}}", "Légende", extra={"font-style": "italic"},
              **{"if": f"images.{index}.caption"})], **{"if": f"images.{index}"})

template("gallery-editorial", "Galerie magazine : une grande photo légendée à gauche, à droite le titre, un court texte "
         "et deux photos décalées. Posé, éditorial.",
    {"title": "titre de section", "text": "2 phrases",
     "images": [{"imageQuery": "mots-clés anglais", "alt": "description", "caption?": "légende courte"}]},
    section(grid("Composition", min_col="320px", gap="48px 72px",
                 extra={"width": "100%", "max-width": "1160px", "grid-template-columns":
                        "repeat(auto-fit, minmax(min(100%, 320px), 1fr))", "align-items": "start"}, children=[
        captioned(0, "4 / 5", "Grande photo"),
        col("Colonne", gap="32px", extra={"padding-top": "32px"}, children=[
            h2(), body("{{text}}", extra={"font-size": "calc(var(--font-size-base) * 1.1)"}),
            grid("Petites photos", min_col="140px", gap="20px", children=[
                captioned(1, "1 / 1", "Photo"),
                captioned(2, "3 / 4", "Photo décalée", extra={"margin-top": "56px"})]),
        ]),
    ])), families=["editorial", "chaleureux"])

# --- Appels à l'action ---

template("cta-cover", "Appel à l'action final sur une grande photo assombrie, titre centré très grand et un bouton. "
         "Pour finir sur une émotion (réserver, venir, essayer).",
    {"title": "titre court", "text?": "1 phrase", "cta": "bouton", "imageQuery": "mots-clés anglais d'une photo d'ambiance"},
    photo_section(inner("Contenu", {"display": "flex", "flex-direction": "column", "align-items": "center",
                                     "text-align": "center", "gap": "24px"}, [
        h2(extra={"font-size": display(6.5, 4.5, 2.2), "line-height": "1.02", "letter-spacing": "-0.03em",
                  "max-width": "16ch", "color": ON_PHOTO}),
        lead("{{text}}", extra={"color": ON_PHOTO_MUTED, "max-width": "44ch"}, **{"if": "text"}),
        button(extra={"align-self": "center", "margin-top": "8px"}),
    ], max_width="900px"), padding="128px 6%", extra={"min-height": "520px", "justify-content": "center"},
        veil_css=veil("to bottom", strong=62, light=62)),
    tone=False, families=["chaleureux", "editorial"])

template("cta-statement", "Appel à l'action manifeste : un très grand titre sous un filet, le texte et le bouton à droite, "
         "sans encadré. Net et confiant.",
    {"title": "titre court et affirmé", "text?": "1 phrase", "cta": "bouton", "secondaryCta?": "lien secondaire"},
    section(grid("Composition", min_col="320px", gap="32px 64px",
                 extra={"width": "100%", "max-width": "1120px", "border-top": "1px solid var(--text)",
                        "padding-top": "48px", "align-items": "end"}, children=[
        h2(extra={"font-size": display(7, 4.5, 2.2), "line-height": "1", "letter-spacing": "-0.035em", "max-width": "14ch"}),
        col("Action", gap="20px", children=[
            lead("{{text}}", extra={"color": "var(--text)"}, **{"if": "text"}),
            actions()]),
    ]), padding="96px 6% 112px", extra=QUERY), families=["audacieux", "editorial"])

# ---------- Ordre du catalogue ----------
ORDER = ["nav", "hero-split", "hero-wide", "hero-cover", "hero-statement", "hero-mosaic", "hero-poster",
         "features", "features-icons", "features-statement", "features-bento", "steps", "text-image", "image-text",
         "gallery", "gallery-editorial", "gallery-mosaic", "price-list", "pricing", "testimonial", "faq", "contact",
         "form", "cta", "cta-cover", "cta-statement", "footer"]
assert sorted(ORDER) == sorted(t["id"] for t in T), set(ORDER) ^ {t["id"] for t in T}
T.sort(key=lambda t: ORDER.index(t["id"]))

# Icônes disponibles : même liste que le frontend (src/core/data/icons.data.ts)
ICONS = ("leaf coffee utensils cake-slice croissant wine salad chef-hat flower-2 sprout sun moon flame droplets waves "
         "wind mountain heart hand-heart heart-handshake smile star sparkles award shield-check check lock key scale "
         "gavel landmark briefcase file-text handshake users baby paw-print stethoscope dumbbell activity timer clock "
         "calendar map-pin phone mail globe wifi house car bike truck gift shopping-bag scissors camera music palette "
         "wrench hammer book-open graduation-cap zap quote").split()


out = {"_source": "Sections conçues pour views-builder. Syntaxe : {{slot}} insère un contenu, \"if\" retire le bloc si le contenu est absent, \"repeat\" répète le premier enfant pour chaque élément d'une liste ({{value}} pour une liste de textes, {{_index}} pour le numéro). {{_bg}} et {{_card}} dépendent du ton choisi (default ou alt).",
       "icons": ICONS,
       "templates": T}
json.dump(out, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
print(len(T), "sections")
