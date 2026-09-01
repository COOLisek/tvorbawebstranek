const startBtn = document.getElementById("start-btn");
const stopBtn = document.getElementById("stop-btn");
const manualBtn = document.getElementById("manual-btn");
const manualInput = document.getElementById("manual-code");
const resultEl = document.getElementById("result");

const GLUTEN_KEYWORDS = [
    "lepek", "gluten",
    "pšenice", "pšeničná", "pšeničný", "wheat",
    "žito", "žitná", "žitný", "rye",
    "ječmen", "ječná", "ječný", "barley",
    "špalda", "spelt",
    "slad", "sladový", "malt",
    "oves", "ovesné", "oats" // oves může být kontaminovaný lepkem, pokud není označen bezlepkový
];

let scanner = null;

function setResultStatus(status, html) {
    resultEl.hidden = false;
    resultEl.className = "result-card status-" + status;
    resultEl.innerHTML = html;
    resultEl.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function showLoading(barcode) {
    setResultStatus("unknown", `<p class="loading"><i class="fa-solid fa-spinner fa-spin"></i> Hledám produkt s kódem ${barcode}…</p>`);
}

function containsGlutenKeyword(text) {
    const lower = text.toLowerCase();
    return GLUTEN_KEYWORDS.some(word => lower.includes(word));
}

function evaluateProduct(product) {
    const labels = (product.labels_tags || []).join(" ");
    const allergens = (product.allergens_tags || []).join(" ");
    const traces = (product.traces_tags || []).join(" ");
    const ingredients = product.ingredients_text_cs || product.ingredients_text || "";
    const name = product.product_name_cs || product.product_name || "Neznámý produkt";

    if (labels.includes("en:gluten-free") || labels.includes("en:no-gluten")) {
        return {
            status: "ok",
            icon: "fa-solid fa-circle-check",
            title: "Bezlepkové",
            detail: "Produkt je na obale označen jako bezlepkový.",
            name, ingredients
        };
    }

    if (allergens.includes("en:gluten")) {
        return {
            status: "warn",
            icon: "fa-solid fa-triangle-exclamation",
            title: "Obsahuje lepek",
            detail: "Produkt má lepek uvedený jako alergen.",
            name, ingredients
        };
    }

    if (traces.includes("en:gluten")) {
        return {
            status: "warn",
            icon: "fa-solid fa-triangle-exclamation",
            title: "Může obsahovat stopy lepku",
            detail: "Výrobce uvádí možnou kontaminaci lepkem.",
            name, ingredients
        };
    }

    if (ingredients && containsGlutenKeyword(ingredients)) {
        return {
            status: "warn",
            icon: "fa-solid fa-triangle-exclamation",
            title: "Pravděpodobně obsahuje lepek",
            detail: "Ve složení jsme našli surovinu, která obvykle obsahuje lepek.",
            name, ingredients
        };
    }

    if (ingredients) {
        return {
            status: "unknown",
            icon: "fa-solid fa-circle-question",
            title: "Lepek nenalezen ve složení",
            detail: "Ve složení jsme nenašli žádnou obvyklou lepkovou surovinu, ale ověř to i na obalu.",
            name, ingredients
        };
    }

    return {
        status: "unknown",
        icon: "fa-solid fa-circle-question",
        title: "Složení není v databázi k dispozici",
        detail: "Zkus se podívat přímo na obal produktu.",
        name, ingredients: ""
    };
}

function renderEvaluation(evaluation) {
    const ingredientsHtml = evaluation.ingredients
        ? `<p class="result-ingredients"><strong>Složení:</strong> ${evaluation.ingredients}</p>`
        : "";

    setResultStatus(evaluation.status, `
        <div class="result-status"><i class="${evaluation.icon}"></i> ${evaluation.title}</div>
        <p class="result-product">${evaluation.name}</p>
        <p>${evaluation.detail}</p>
        ${ingredientsHtml}
    `);
}

async function lookupBarcode(barcode) {
    barcode = barcode.trim();
    if (!barcode) return;

    showLoading(barcode);

    try {
        const response = await fetch(`https://world.openfoodfacts.org/api/v2/product/${encodeURIComponent(barcode)}.json`);
        if (!response.ok) {
            throw new Error("Chyba sítě");
        }
        const data = await response.json();

        if (data.status !== 1 || !data.product) {
            setResultStatus("unknown", `
                <div class="result-status"><i class="fa-solid fa-circle-question"></i> Produkt nenalezen</div>
                <p>Kód ${barcode} nemáme v databázi Open Food Facts. Zkontroluj složení přímo na obalu.</p>
            `);
            return;
        }

        renderEvaluation(evaluateProduct(data.product));
    } catch (err) {
        setResultStatus("unknown", `
            <div class="result-status"><i class="fa-solid fa-triangle-exclamation"></i> Vyhledání se nezdařilo</div>
            <p>Zkontroluj připojení k internetu a zkus to znovu.</p>
        `);
    }
}

function onScanSuccess(decodedText) {
    lookupBarcode(decodedText);
    stopScanner();
}

function startScanner() {
    if (scanner) return;

    scanner = new Html5Qrcode("reader");
    const config = {
        fps: 10,
        qrbox: { width: 250, height: 150 },
        formatsToSupport: [
            Html5QrcodeSupportedFormats.EAN_13,
            Html5QrcodeSupportedFormats.EAN_8,
            Html5QrcodeSupportedFormats.UPC_A,
            Html5QrcodeSupportedFormats.UPC_E
        ]
    };

    scanner.start({ facingMode: "environment" }, config, onScanSuccess)
        .then(() => {
            startBtn.hidden = true;
            stopBtn.hidden = false;
        })
        .catch(() => {
            setResultStatus("unknown", `
                <div class="result-status"><i class="fa-solid fa-triangle-exclamation"></i> Kamera není dostupná</div>
                <p>Zkontroluj, že jsi povolil přístup ke kameře, nebo zadej čárový kód ručně níže.</p>
            `);
            scanner = null;
        });
}

function stopScanner() {
    if (!scanner) return;
    scanner.stop().then(() => scanner.clear()).catch(() => { });
    scanner = null;
    startBtn.hidden = false;
    stopBtn.hidden = true;
}

startBtn.addEventListener("click", startScanner);
stopBtn.addEventListener("click", stopScanner);

manualBtn.addEventListener("click", () => lookupBarcode(manualInput.value));
manualInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") lookupBarcode(manualInput.value);
});
