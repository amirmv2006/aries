##    Licensed to the Apache Software Foundation (ASF) under one or more
##    contributor license agreements.  See the NOTICE file distributed with
##    this work for additional information regarding copyright ownership.
##    The ASF licenses this file to You under the Apache License, Version 2.0
##    (the "License"); you may not use this file except in compliance with
##    the License.  You may obtain a copy of the License at
##
##       http://www.apache.org/licenses/LICENSE-2.0
##
##    Unless required by applicable law or agreed to in writing, software
##    distributed under the License is distributed on an "AS IS" BASIS,
##    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
##    See the License for the specific language governing permissions and
##    limitations under the License.

# Each SQL statement in this file should terminate with a semicolon (;)
# Lines starting with the pound character (#) are considered as comments
DROP TABLE HOLDINGEJB cascade constraints;
DROP TABLE ACCOUNTPROFILEEJB cascade constraints;
DROP TABLE QUOTEEJB cascade constraints;
DROP TABLE KEYGENEJB cascade constraints;
DROP TABLE ACCOUNTEJB cascade constraints;
DROP TABLE ORDEREJB cascade constraints;

CREATE TABLE HOLDINGEJB
  (PURCHASEPRICE DECIMAL(14, 2) NULL,
   HOLDINGID INTEGER NOT NULL,
   QUANTITY NUMBER NOT NULL,
   PURCHASEDATE DATE NULL,
   ACCOUNT_ACCOUNTID INTEGER NULL,
   QUOTE_SYMBOL VARCHAR2(255) NULL);

ALTER TABLE HOLDINGEJB
  ADD CONSTRAINT PK_HOLDINGEJB PRIMARY KEY (HOLDINGID);

CREATE TABLE ACCOUNTPROFILEEJB
  (ADDRESS VARCHAR2(255) NULL,
   PASSWD VARCHAR2(255) NULL,
   USERID VARCHAR2(255) NOT NULL,
   EMAIL VARCHAR2(255) NULL,
   CREDITCARD VARCHAR2(255) NULL,
   FULLNAME VARCHAR2(255) NULL);

ALTER TABLE ACCOUNTPROFILEEJB
  ADD CONSTRAINT PK_ACCOUNTPROFILEEJB PRIMARY KEY (USERID);

CREATE TABLE QUOTEEJB
  (LOW DECIMAL(14, 2) NULL,
   OPEN1 DECIMAL(14, 2) NULL,
   VOLUME NUMBER NOT NULL,
   PRICE DECIMAL(14, 2) NULL,
   HIGH DECIMAL(14, 2) NULL,
   COMPANYNAME VARCHAR2(255) NULL,
   SYMBOL VARCHAR2(255) NOT NULL,
   CHANGE1 NUMBER NOT NULL);

ALTER TABLE QUOTEEJB
  ADD CONSTRAINT PK_QUOTEEJB PRIMARY KEY (SYMBOL);

CREATE TABLE KEYGENEJB
  (KEYVAL INTEGER NOT NULL,
   KEYNAME VARCHAR2(255) NOT NULL);

ALTER TABLE KEYGENEJB
  ADD CONSTRAINT PK_KEYGENEJB PRIMARY KEY (KEYNAME);

CREATE TABLE ACCOUNTEJB
  (CREATIONDATE DATE NULL,
   OPENBALANCE DECIMAL(14, 2) NULL,
   LOGOUTCOUNT INTEGER NOT NULL,
   BALANCE DECIMAL(14, 2) NULL,
   ACCOUNTID INTEGER NOT NULL,
   LASTLOGIN DATE NULL,
   LOGINCOUNT INTEGER NOT NULL,
   PROFILE_USERID VARCHAR2(255) NULL);

ALTER TABLE ACCOUNTEJB
  ADD CONSTRAINT PK_ACCOUNTEJB PRIMARY KEY (ACCOUNTID);

CREATE TABLE ORDEREJB
  (ORDERFEE DECIMAL(14, 2) NULL,
   COMPLETIONDATE DATE NULL,
   ORDERTYPE VARCHAR2(255) NULL,
   ORDERSTATUS VARCHAR2(255) NULL,
   PRICE DECIMAL(14, 2) NULL,
   QUANTITY NUMBER NOT NULL,
   OPENDATE DATE NULL,
   ORDERID INTEGER NOT NULL,
   ACCOUNT_ACCOUNTID INTEGER NULL,
   QUOTE_SYMBOL VARCHAR2(255) NULL,
   HOLDING_HOLDINGID INTEGER NULL);

ALTER TABLE ORDEREJB
  ADD CONSTRAINT PK_ORDEREJB PRIMARY KEY (ORDERID);

CREATE INDEX ACCOUNT_USERID ON ACCOUNTEJB(PROFILE_USERID);
CREATE INDEX HOLDING_ACCOUNTID ON HOLDINGEJB(ACCOUNT_ACCOUNTID);
CREATE INDEX ORDER_ACCOUNTID ON ORDEREJB(ACCOUNT_ACCOUNTID);
CREATE INDEX ORDER_HOLDINGID ON ORDEREJB(HOLDING_HOLDINGID);
CREATE INDEX CLOSED_ORDERS ON ORDEREJB(ACCOUNT_ACCOUNTID,ORDERSTATUS);
