# ТОР «Моя школа» — dashboard

Публичный дашборд статистики по МСУ и образовательным организациям, а также административный импорт Excel-снимков.

## Стек

- Backend: Java 21, Spring Boot, Spring Security, Spring Data JPA, PostgreSQL, Liquibase, Apache POI.
- Frontend: Next.js App Router, TypeScript, Tailwind CSS, Recharts, TanStack Query.

## Требования

- PostgreSQL 16+
- Java 21+
- Node.js 22+

## Структура

- `backend/` — REST API, импорт Excel, схема БД.
- `frontend/` — публичный дашборд и админ-панель.
- `docker-compose.yml` — локальная инфраструктура PostgreSQL и приложений.

## Конфигурация

Скопируйте `.env.example` в `.env` и задайте секрет администратора и пароль PostgreSQL. Excel-файлы не сохраняются: сервер обрабатывает загрузку во временном каталоге и удаляет временный файл после завершения.

Подробная инструкция по запуску будет дополняться по мере реализации.