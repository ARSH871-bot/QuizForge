-- V1: Baseline schema, transcribed from the entity definitions that
-- Hibernate previously created via ddl-auto=update. From this point the
-- schema is owned by Flyway and Hibernate only validates against it.

CREATE TABLE users (
    id                   BIGSERIAL PRIMARY KEY,
    username             VARCHAR(255) NOT NULL UNIQUE,
    email                VARCHAR(255) NOT NULL UNIQUE,
    password             VARCHAR(255) NOT NULL,
    role                 VARCHAR(255) NOT NULL,
    password_reset_token VARCHAR(255),
    first_name           VARCHAR(255),
    last_name            VARCHAR(255),
    profile_picture      VARCHAR(255),
    phone_number         VARCHAR(255),
    city                 VARCHAR(255),
    occupation           VARCHAR(255),
    preferred_language   VARCHAR(255),
    address              VARCHAR(255),
    date_of_birth        DATE,
    gender               VARCHAR(255),
    country              VARCHAR(255),
    bio                  VARCHAR(1000)
);

CREATE TABLE quiz (
    id                     BIGSERIAL PRIMARY KEY,
    name                   VARCHAR(255),
    category               VARCHAR(255),
    difficulty             VARCHAR(255),
    start_date             TIMESTAMP(6),
    end_date               TIMESTAMP(6),
    minimum_passing_score  FLOAT(53),
    likes_count            INTEGER NOT NULL DEFAULT 0,
    rating                 FLOAT(53) DEFAULT 0.0,
    rating_count           INTEGER DEFAULT 0,
    user_id                BIGINT REFERENCES users (id)
);

CREATE TABLE question (
    id             BIGSERIAL PRIMARY KEY,
    question_text  VARCHAR(255),
    correct_answer VARCHAR(255),
    quiz_id        BIGINT REFERENCES quiz (id)
);

CREATE TABLE question_options (
    question_id BIGINT NOT NULL REFERENCES question (id),
    options     VARCHAR(255)
);

CREATE TABLE score (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users (id),
    quiz_id        BIGINT NOT NULL REFERENCES quiz (id),
    score          FLOAT(53) NOT NULL,
    completed_date TIMESTAMP(6)
);

CREATE TABLE participation (
    id      BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id),
    quiz_id BIGINT REFERENCES quiz (id)
);

CREATE TABLE quiz_likes (
    id       BIGSERIAL PRIMARY KEY,
    user_id  BIGINT NOT NULL REFERENCES users (id),
    quiz_id  BIGINT NOT NULL REFERENCES quiz (id),
    liked_at TIMESTAMP(6),
    CONSTRAINT uk_quiz_likes_user_quiz UNIQUE (user_id, quiz_id)
);

CREATE TABLE categories (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE INDEX idx_question_quiz_id      ON question (quiz_id);
CREATE INDEX idx_score_user_id         ON score (user_id);
CREATE INDEX idx_score_quiz_id         ON score (quiz_id);
CREATE INDEX idx_participation_user_id ON participation (user_id);
CREATE INDEX idx_participation_quiz_id ON participation (quiz_id);
CREATE INDEX idx_quiz_start_end        ON quiz (start_date, end_date);
