ALTER TABLE service_plans
    ADD COLUMN plan_type VARCHAR(32) NOT NULL DEFAULT 'SUBSCRIPTION',
    ADD COLUMN resettable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN purchase_limit_per_user INTEGER;

UPDATE service_plans plan
SET plan_type = 'TRAFFIC_PACKAGE'
WHERE plan.reset_policy = 'NEVER'
  AND EXISTS (
      SELECT 1
      FROM service_plan_prices price
      WHERE price.plan_id = plan.id
        AND price.billing_period = 'ONETIME'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM service_plan_prices price
      WHERE price.plan_id = plan.id
        AND price.billing_period <> 'ONETIME'
  );

UPDATE service_plans
SET renewable = FALSE
WHERE plan_type = 'TRAFFIC_PACKAGE';

ALTER TABLE service_plans
    ADD CONSTRAINT ck_service_plans_type
        CHECK (plan_type IN ('SUBSCRIPTION', 'TRAFFIC_PACKAGE')),
    ADD CONSTRAINT ck_service_plans_purchase_limit
        CHECK (
            purchase_limit_per_user IS NULL
            OR purchase_limit_per_user > 0
        ),
    ADD CONSTRAINT ck_service_plans_package_reset
        CHECK (
            plan_type <> 'TRAFFIC_PACKAGE'
            OR reset_policy = 'NEVER'
        ),
    ADD CONSTRAINT ck_service_plans_package_fields
        CHECK (
            plan_type = 'TRAFFIC_PACKAGE'
            OR (
                resettable = FALSE
                AND purchase_limit_per_user IS NULL
            )
        ),
    ADD CONSTRAINT ck_service_plans_package_renewable
        CHECK (
            plan_type <> 'TRAFFIC_PACKAGE'
            OR renewable = FALSE
        );

ALTER TABLE service_plan_prices
    DROP CONSTRAINT ck_service_plan_period;

ALTER TABLE service_plan_prices
    ADD CONSTRAINT ck_service_plan_period CHECK (billing_period IN (
        'MONTHLY',
        'QUARTERLY',
        'HALF_YEARLY',
        'YEARLY',
        'TWO_YEARLY',
        'THREE_YEARLY',
        'ONETIME',
        'RESET_TRAFFIC'
    ));
