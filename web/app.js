(() => {
  "use strict";

  const DATA_ROOT = new URL("./schedule-data/", window.location.href);
  const MAX_RESULTS = 100;
  const INDEX_CACHE_TTL_MS = 12 * 60 * 60 * 1000;
  const CSV_HEADER_LABELS = {
    date: "Дата",
    lesson_date: "Дата",
    start_time: "Начало",
    end_time: "Окончание",
    subject: "Предмет",
    subject_name: "Предмет",
    teacher: "Преподаватель",
    teacher_name: "Преподаватель",
    classroom: "Аудитория",
    room: "Аудитория",
    type: "Тип занятия",
  };
  const indexCache = new Map();
  let universities = [];
  let serviceWorkerRegistrationPromise = null;

  const universitySelect = document.querySelector("#university");
  const form = document.querySelector("#search-form");
  const queryInput = document.querySelector("#query");
  const typeInput = document.querySelector("#profile-type");
  const queryLabel = document.querySelector("#query-label");
  const searchButton = document.querySelector("#search-button");
  const statusBox = document.querySelector("#status");
  const resultsSection = document.querySelector("#results-section");
  const resultsBox = document.querySelector("#results");
  const resultCount = document.querySelector("#result-count");
  const scheduleSection = document.querySelector("#schedule-section");
  const scheduleTitle = document.querySelector("#schedule-title");
  const scheduleSubtitle = document.querySelector("#schedule-subtitle");
  const scheduleMeta = document.querySelector("#schedule-meta");
  const scheduleContent = document.querySelector("#schedule-content");

  function setStatus(message, kind = "") {
    statusBox.textContent = message;
    statusBox.className = `status${kind ? ` ${kind}` : ""}`;
  }

  function reportError(message, code, error) {
    console.error(`[${code}] ${message}`, error);
    setStatus(`${message} Код: ${code}. Если повторится, пришлите скриншот и опишите действие.`, "error");
  }

  function normalize(value) {
    return String(value || "")
      .normalize("NFC")
      .toLocaleLowerCase("ru-RU")
      .replaceAll("ё", "е")
      .replace(/\s+/g, " ")
      .trim();
  }

  function parseCsv(text) {
    const rows = [];
    let row = [];
    let field = "";
    let quoted = false;
    const input = text.replace(/^\uFEFF/, "");
    for (let i = 0; i < input.length; i += 1) {
      const char = input[i];
      if (char === '"' && quoted && input[i + 1] === '"') {
        field += '"';
        i += 1;
      } else if (char === '"') {
        quoted = !quoted;
      } else if (char === "," && !quoted) {
        row.push(field.trim());
        field = "";
      } else if ((char === "\n" || char === "\r") && !quoted) {
        row.push(field.trim());
        if (row.some((cell) => cell !== "")) rows.push(row);
        row = [];
        field = "";
        if (char === "\r" && input[i + 1] === "\n") i += 1;
      } else {
        field += char;
      }
    }
    if (quoted) throw new Error("CSV-файл повреждён: не закрыта кавычка.");
    if (field !== "" || row.length) {
      row.push(field.trim());
      if (row.some((cell) => cell !== "")) rows.push(row);
    }
    if (!rows.length) return [];
    const headers = rows.shift().map((header) => header.trim().toLowerCase().replaceAll("ё", "е"));
    return rows.map((cells) => Object.fromEntries(headers.map((header, index) => [header, cells[index] || ""])));
  }

  function safePath(value, prefix) {
    const path = String(value || "").replaceAll("\\", "/");
    const segments = path.split("/");
    return path.startsWith(prefix) && segments.every((part) => part && part !== "." && part !== "..")
      ? path
      : null;
  }

  async function fetchText(url) {
    const response = await fetch(url, { headers: { Accept: "text/plain,text/html,*/*;q=0.8" } });
    if (!response.ok) throw new Error(`GitHub вернул HTTP ${response.status}.`);
    return response.text();
  }

  function registerServiceWorkerAfterUserAction() {
    if (!("serviceWorker" in navigator) || !location.protocol.startsWith("http")) return Promise.resolve(false);
    if (!serviceWorkerRegistrationPromise) {
      serviceWorkerRegistrationPromise = navigator.serviceWorker.register("./sw.js")
        .then(() => Promise.race([
          navigator.serviceWorker.ready.then(() => true),
          new Promise((resolve) => setTimeout(() => resolve(false), 3000)),
        ]))
        .catch(() => false);
    }
    return serviceWorkerRegistrationPromise;
  }

  function initializeUniversities() {
    universities = [...universitySelect.options].map((option) => ({ id: option.value, name: option.textContent }));
    const saved = localStorage.getItem("schedule.selected-university");
    if (universities.some((item) => item.id === saved)) universitySelect.value = saved;
    universitySelect.addEventListener("change", () => {
      localStorage.setItem("schedule.selected-university", universitySelect.value);
      resultsSection.hidden = true;
      scheduleSection.hidden = true;
    });
  }

  function currentType() {
    return typeInput.value === "teachers" ? "teachers" : "groups";
  }

  async function getIndex(universityId, profileType) {
    const cacheKey = `${universityId}/${profileType}`;
    const cached = indexCache.get(cacheKey);
    if (cached && Date.now() - cached.loadedAt < INDEX_CACHE_TTL_MS) return cached.records;
    const url = new URL(`universities/${encodeURIComponent(universityId)}/${profileType}/index.csv`, DATA_ROOT);
    try {
      const records = parseCsv(await fetchText(url));
      indexCache.set(cacheKey, { loadedAt: Date.now(), records });
      return records;
    } catch (error) {
      if (cached) return cached.records;
      throw error;
    }
  }

  function renderResults(candidates) {
    resultsBox.replaceChildren();
    resultCount.textContent = String(candidates.length);
    resultsSection.hidden = false;
    if (!candidates.length) {
      const empty = document.createElement("p");
      empty.className = "empty-message";
      empty.textContent = "Совпадений в опубликованном индексе нет.";
      resultsBox.append(empty);
      return;
    }
    for (const candidate of candidates) {
      const available = normalize(candidate.status) === "ready";
      const card = document.createElement("button");
      card.type = "button";
      card.className = "result-card";
      card.disabled = !available;
      const copy = document.createElement("span");
      const title = document.createElement("p");
      title.className = "result-name";
      title.textContent = candidate.name || "Без названия";
      const detail = document.createElement("p");
      detail.className = "result-detail";
      detail.textContent = [candidate.subtitle, available ? `Обновлено ${candidate.updated_at || "—"}` : "Файл расписания ещё не опубликован"]
        .filter(Boolean)
        .join(" · ");
      copy.append(title, detail);
      const action = document.createElement("span");
      action.className = "result-action";
      action.textContent = available ? "＋" : "скоро";
      card.append(copy, action);
      if (available) card.addEventListener("click", () => loadSchedule(candidate));
      resultsBox.append(card);
    }
  }

  async function search(event) {
    event.preventDefault();
    const query = normalize(queryInput.value);
    const universityId = universitySelect.value;
    const profileType = currentType();
    if (!universityId || !query) {
      setStatus("Выберите университет и введите хотя бы один символ.", "error");
      return;
    }
    searchButton.disabled = true;
    searchButton.textContent = "Ищем…";
    scheduleSection.hidden = true;
    setStatus("Читаем индекс GitHub; сайты вузов не запрашиваются.");
    try {
      await registerServiceWorkerAfterUserAction();
      const index = await getIndex(universityId, profileType);
      const matches = index
        .filter((item) => normalize(item.name).includes(query) || normalize(item.subtitle).includes(query))
        .sort((left, right) => {
          const leftStarts = normalize(left.name).startsWith(query) ? 0 : 1;
          const rightStarts = normalize(right.name).startsWith(query) ? 0 : 1;
          const readyOrder = (normalize(left.status) === "ready" ? 0 : 1) - (normalize(right.status) === "ready" ? 0 : 1);
          return leftStarts - rightStarts || readyOrder || normalize(left.name).localeCompare(normalize(right.name), "ru");
        })
        .slice(0, MAX_RESULTS);
      renderResults(matches);
      setStatus(matches.length ? `Найдено совпадений: ${matches.length}.` : "Совпадений нет; каталог можно пополнить через Python-скрипты.", matches.length ? "success" : "");
    } catch (error) {
      reportError("Не удалось загрузить каталог расписаний.", "WEB-SEARCH", error);
    } finally {
      searchButton.disabled = false;
      searchButton.textContent = "Найти";
    }
  }

  function createTable(headers, rows) {
    const table = document.createElement("table");
    if (headers.length) {
      const thead = document.createElement("thead");
      const headerRow = document.createElement("tr");
      for (const label of headers) {
        const th = document.createElement("th");
        th.textContent = label;
        headerRow.append(th);
      }
      thead.append(headerRow);
      table.append(thead);
    }
    const tbody = document.createElement("tbody");
    for (const row of rows) {
      const tr = document.createElement("tr");
      for (const value of row) {
        const td = document.createElement("td");
        td.textContent = value;
        tr.append(td);
      }
      tbody.append(tr);
    }
    table.append(tbody);
    return table;
  }

  function renderCsvSchedule(text) {
    const rows = parseCsv(text);
    if (!rows.length) throw new Error("Файл расписания пуст.");
    const fields = Object.keys(rows[0]).filter((header) => !["source_key", "id"].includes(header));
    const headers = fields.map((header) => CSV_HEADER_LABELS[header] || header.replaceAll("_", " "));
    const body = rows.slice(0, 1000).map((row) => fields.map((header) => {
      const value = row[header] || "";
      if (header === "date" && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
        return `${value.slice(8, 10)}.${value.slice(5, 7)}.${value.slice(0, 4)}`;
      }
      return value;
    }));
    scheduleContent.append(createTable(headers, body));
  }

  function cleanCellText(cell) {
    const copy = cell.cloneNode(true);
    copy.querySelectorAll("br").forEach((br) => br.replaceWith(document.createTextNode(" · ")));
    return (copy.textContent || "").replace(/\s+/g, " ").trim();
  }

  function renderHtmlSchedule(text) {
    const parsed = new DOMParser().parseFromString(text, "text/html");
    const tables = [...parsed.querySelectorAll("table")];
    const table = tables.find((item) => item.classList.contains("schedule-table")) ||
      tables.find((item) => item.querySelectorAll("tr").length > 1);
    if (!table) {
      const note = document.createElement("p");
      note.className = "empty-message";
      note.textContent = "Файл расписания получен, но таблица не распознана веб-версией. Откройте файл напрямую.";
      scheduleContent.append(note);
      return false;
    }
    const rows = [...table.querySelectorAll("tr")].slice(0, 400).map((row) =>
      [...row.querySelectorAll(":scope > th, :scope > td")].map(cleanCellText),
    ).filter((row) => row.length && row.some(Boolean));
    if (!rows.length) return false;
    const first = rows[0];
    const hasHeader = table.querySelector("thead") !== null || [...table.querySelectorAll("tr")[0].children].some((cell) => cell.tagName === "TH");
    scheduleContent.append(createTable(hasHeader ? first : [], hasHeader ? rows.slice(1) : rows));
    return true;
  }

  async function loadSchedule(candidate) {
    const universityId = universitySelect.value;
    const profileType = currentType();
    const path = safePath(candidate.schedule_path, `schedules/${profileType}/`);
    if (!path) {
      reportError("В каталоге указан недопустимый путь файла.", "WEB-PATH");
      return;
    }
    const url = new URL(`universities/${encodeURIComponent(universityId)}/${path.split("/").map(encodeURIComponent).join("/")}`, DATA_ROOT);
    scheduleTitle.textContent = candidate.name;
    scheduleSubtitle.textContent = profileType === "groups" ? "РАСПИСАНИЕ ГРУППЫ" : "РАСПИСАНИЕ ПРЕПОДАВАТЕЛЯ";
    scheduleMeta.textContent = [candidate.subtitle, candidate.updated_at ? `Версия данных: ${candidate.updated_at}` : ""].filter(Boolean).join(" · ");
    scheduleContent.replaceChildren();
    scheduleSection.hidden = false;
    scheduleSection.scrollIntoView({ behavior: "smooth", block: "start" });
    setStatus("Загружаем копию расписания из GitHub…");
    try {
      const response = await fetch(url);
      if (!response.ok) throw new Error(`GitHub вернул HTTP ${response.status}.`);
      const content = await response.text();
      if (url.pathname.toLowerCase().endsWith(".csv")) {
        renderCsvSchedule(content);
      } else if (/\.html?$/i.test(url.pathname)) {
        const rendered = renderHtmlSchedule(content);
        if (!rendered) {
          const link = document.createElement("a");
          link.className = "open-file";
          link.href = url.href;
          link.target = "_blank";
          link.rel = "noopener";
          link.textContent = "Открыть исходный файл расписания";
          scheduleContent.append(link);
        }
      } else {
        const link = document.createElement("a");
        link.className = "open-file";
        link.href = url.href;
        link.target = "_blank";
        link.rel = "noopener";
        link.textContent = "Открыть файл расписания";
        scheduleContent.append(link);
      }
      setStatus("Расписание загружено из GitHub и сохранено браузером.", "success");
    } catch (error) {
      const isMissingFile = String(error.message).includes("HTTP 404");
      reportError(
        isMissingFile
          ? "Файл больше не опубликован. Кэшированное расписание сохранено на устройстве."
          : "Не удалось открыть расписание. Сохранённые данные не удалены.",
        isMissingFile ? "WEB-DATA-404" : "WEB-SCHEDULE",
        error,
      );
    }
  }

  document.querySelectorAll(".segment").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll(".segment").forEach((item) => {
        const selected = item === button;
        item.classList.toggle("is-active", selected);
        item.setAttribute("aria-pressed", String(selected));
      });
      typeInput.value = button.dataset.type;
      const isGroup = currentType() === "groups";
      queryLabel.textContent = isGroup ? "Код или название группы" : "Имя преподавателя";
      queryInput.placeholder = isGroup ? "Например, МПР-261 или 41" : "Например, Агеева Т.Н.";
      resultsSection.hidden = true;
      scheduleSection.hidden = true;
    });
  });

  document.querySelector("#close-schedule").addEventListener("click", () => {
    scheduleSection.hidden = true;
  });
  form.addEventListener("submit", search);

  initializeUniversities();
})();
