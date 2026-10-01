#!/bin/sh
set -e

# backend/.env의 DB_URL은 각자 컴퓨터의 CA 경로를 가리킨다. 컨테이너 안에서는 이미지에 넣은 CA로 바꾼다.
if [ -n "$DB_URL" ]; then
  case "$DB_URL" in
    *sslrootcert=*)
      DB_URL=$(printf '%s' "$DB_URL" | sed -E 's#sslrootcert=[^&]*#sslrootcert=/certs/rds-global-bundle.pem#')
      export DB_URL
      ;;
  esac
fi

exec java $JAVA_OPTS -jar /app/app.jar "$@"
