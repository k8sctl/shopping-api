#!/bin/bash
# 환경변수로 받기
MYSQL_ROOT_PASSWORD=$(kubectl get secret mysql-secret -n shopping-api \
  -o jsonpath='{.data.MYSQL_ROOT_PASSWORD}' | base64 -d)

kubectl exec mysql-0 -n shopping-api -- \
  mysql -uroot -p${MYSQL_ROOT_PASSWORD} -e \
  "USE shopping_dev;
   SET FOREIGN_KEY_CHECKS=0;
   TRUNCATE TABLE orders;
   TRUNCATE TABLE products;
   TRUNCATE TABLE users;
   SET FOREIGN_KEY_CHECKS=1;"

echo "DB 초기화 완료"