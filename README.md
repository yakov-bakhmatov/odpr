odpr
====

Ложные корреляции по открытым данным Пермского края

1. Загрузить данные

lein run load-data

2. Подготовить данные

lein run prepare-data

3. Вычислить попарные корреляции

lein run calculate

4. Отфильтровать корреляции и данные

lein run prepare-correlation

5. Запустить веб-сервер

lein run serve
