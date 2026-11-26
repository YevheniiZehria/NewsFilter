-- ==========================================================
-- Schema PostgreSQL pentru NewsFilter (Backend n8n)
-- ==========================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. Tabela de articole
CREATE TABLE IF NOT EXISTS articles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(500) NOT NULL,
    source VARCHAR(100) NOT NULL,
    image_url TEXT,
    summary TEXT NOT NULL,
    article_url TEXT NOT NULL UNIQUE,
    read_time_minutes INT NOT NULL DEFAULT 2,
    category VARCHAR(50) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Textul integral al articolului (extras din pagina web prin scraping)
    full_content TEXT,
    -- Rezumatul generat de AI (Gemini) în română
    ai_summary TEXT
);

-- Indecși pentru performanță la interogare și filtrare
CREATE INDEX IF NOT EXISTS idx_articles_category ON articles(category);
CREATE INDEX IF NOT EXISTS idx_articles_published_at ON articles(published_at DESC);
CREATE INDEX IF NOT EXISTS idx_articles_read_time ON articles(read_time_minutes);

-- 2. Tabela de feedback (like / dislike de la utilizatori)
CREATE TABLE IF NOT EXISTS article_feedback (
    id SERIAL PRIMARY KEY,
    article_id UUID NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    is_positive BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feedback_article ON article_feedback(article_id);

-- 3. Tabela de utilizatori (autentificare din aplicația Android)
--    password_hash: bcrypt generat cu crypt(parola, gen_salt('bf')) din pgcrypto;
--    NULL pentru conturile create cu Google (auth_provider = 'google').
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    password_hash TEXT,
    auth_provider VARCHAR(20) NOT NULL DEFAULT 'email'
        CHECK (auth_provider IN ('email', 'google')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ==========================================================
-- v2: sesiuni per utilizator, rezumat AI structurat, acțiuni din aplicație.
-- Toate instrucțiunile sunt idempotente: fișierul poate fi rulat din nou peste
-- o bază de date existentă (vezi README → „Actualizare bază de date”).
-- ==========================================================

-- 4. Sesiuni: token opac (256 biți) emis de n8n la autentificare.
--    Se stochează DOAR hash-ul SHA-256 al token-ului; token-ul în clar există doar
--    în răspunsul de login și pe telefon (criptat cu Android Keystore).
CREATE TABLE IF NOT EXISTS sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '30 days',
    revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

-- 5. Smart Digest: 2-3 idei cheie (TL;DR) + etichete, generate de Gemini în workflow 1;
--    deep_dive = analiza „Aprofundează subiectul”, generată la cerere și păstrată în cache.
ALTER TABLE articles ADD COLUMN IF NOT EXISTS ai_bullets JSONB;
ALTER TABLE articles ADD COLUMN IF NOT EXISTS ai_tags JSONB;
ALTER TABLE articles ADD COLUMN IF NOT EXISTS deep_dive TEXT;

-- 6. Favorite per utilizator (sincronizate între dispozitive / reinstalări).
CREATE TABLE IF NOT EXISTS bookmarks (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    article_id UUID NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, article_id)
);

-- 7. Feedback-ul este acum asociat utilizatorului care a votat.
ALTER TABLE article_feedback ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES users(id) ON DELETE SET NULL;

-- 8. Jurnalul cererilor „Forțează actualizarea” (limitare: o rulare la 3 minute).
CREATE TABLE IF NOT EXISTS refresh_requests (
    id SERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_refresh_requests_time ON refresh_requests(requested_at DESC);

-- 9. Corecție date: workflow-ul de ingestie salva categoriile ca „Technology”, „Economy”…,
--    dar aplicația filtrează după slug-uri („tech”, „economy”…).
UPDATE articles SET category = CASE lower(category)
    WHEN 'technology' THEN 'tech'
    ELSE lower(category)
END
WHERE category <> lower(category) OR lower(category) = 'technology';

-- 10. Market Pulse: cursuri valutare live (actualizate de workflow 1, ramura „Curs valutar”)
--     + impactul estimat de AI al fiecărei știri asupra unei perechi valutare.
CREATE TABLE IF NOT EXISTS market_rates (
    pair VARCHAR(16) PRIMARY KEY,            -- 'EUR/RON', 'USD/RON', 'BTC/USD'
    rate NUMERIC(20,8) NOT NULL,
    change_percent NUMERIC(10,4),            -- față de ziua BCE anterioară (fiat) / ultimele 24h (crypto)
    source VARCHAR(40) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE articles ADD COLUMN IF NOT EXISTS affected_currency VARCHAR(16);
ALTER TABLE articles ADD COLUMN IF NOT EXISTS market_sentiment VARCHAR(8)
    CHECK (market_sentiment IN ('UP', 'DOWN', 'NEUTRAL'));
ALTER TABLE articles ADD COLUMN IF NOT EXISTS market_impact_note TEXT;
CREATE INDEX IF NOT EXISTS idx_articles_market ON articles(published_at DESC)
    WHERE affected_currency IS NOT NULL;

-- 11. Istoricul cursurilor (graficul widget-ului valutar). Workflow 1 adaugă un punct la minut la
--     fiecare actualizare din market_rates (păstrat 30 de zile) și puncte zilnice (is_daily = TRUE:
--     BCE pe 5 ani, BTC pe 365 de zile; păstrate 5 ani) pentru intervalele 1 A / Max.
--     Indexul unic servește și interogarea (pair, recorded_at DESC) și ON CONFLICT.
CREATE TABLE IF NOT EXISTS market_rate_history (
    id BIGSERIAL PRIMARY KEY,
    pair VARCHAR(16) NOT NULL,
    rate NUMERIC(20,8) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_market_rate_history_pair_time
    ON market_rate_history(pair, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_market_rate_history_time ON market_rate_history(recorded_at);
ALTER TABLE market_rate_history ADD COLUMN IF NOT EXISTS is_daily BOOLEAN NOT NULL DEFAULT FALSE;

-- ==========================================================
-- Date inițiale (Seed) pentru testare imediată
-- ==========================================================
INSERT INTO articles (title, source, image_url, summary, article_url, read_time_minutes, category, published_at)
VALUES
(
    'Noile cipuri neuronale promit o autonomie triplă pentru smartphone-uri',
    'TechRadar',
    'https://images.unsplash.com/photo-1518770660439-4636190af475?w=800',
    'Cercetătorii au prezentat o nouă arhitectură de procesoare dedicată sarcinilor de inteligență artificială direct pe dispozitiv. Aceasta reduce consumul energetic cu 65% în comparație cu modelele actuale.',
    'https://example.com/tech-news-1',
    2,
    'tech',
    NOW() - INTERVAL '15 minutes'
),
(
    'România a încheiat campania europeană cu o victorie decisivă',
    'GSP',
    'https://images.unsplash.com/photo-1461896836934-ffe607ba8211?w=800',
    'Echipa națională a obținut calificarea după un meci spectaculos în fața propriilor suporteri. Golurile marcate în repriza a doua au asigurat prima poziție în grupă.',
    'https://example.com/sport-news-1',
    1,
    'sport',
    NOW() - INTERVAL '40 minutes'
),
(
    'Banca Națională menține dobânda cheie: ce înseamnă pentru credite',
    'Ziarul Financiar',
    'https://images.unsplash.com/photo-1611974789855-9c2a0a7236a3?w=800',
    'Consiliul de Administrație al BNR a decis păstrarea ratei dobânzii de politică monetară. Analiștii estimează o stabilizare a indicilor ROBOR și IRCC pe parcursul trimestrului următor.',
    'https://example.com/economy-news-1',
    2,
    'economy',
    NOW() - INTERVAL '1 hour'
),
(
    'Telescopul James Webb a descoperit o atmosferă bogată în vapori de apă pe o exoplanetă',
    'ScienceDaily',
    'https://images.unsplash.com/photo-1451187580459-43490279c0fa?w=800',
    'Datele spectrografice confirmă prezența compușilor esențiali vieții în atmosfera unei planete aflate la 120 de ani-lumină. Este cea mai detaliată analiză obținută până în prezent.',
    'https://example.com/science-news-1',
    3,
    'science',
    NOW() - INTERVAL '2 hours'
),
(
    'Proiect major de infrastructură aprobat pentru fluidizarea traficului regional',
    'HotNews',
    'https://images.unsplash.com/photo-1545459720-aac8509eb02c?w=800',
    'Au fost semnate contractele de execuție pentru noua centură ocolitoare și pasajele subterane. Lucrările sunt programate să înceapă luna viitoare cu finanțare europeană.',
    'https://example.com/local-news-1',
    2,
    'local',
    NOW() - INTERVAL '3 hours'
)
ON CONFLICT (article_url) DO NOTHING;

