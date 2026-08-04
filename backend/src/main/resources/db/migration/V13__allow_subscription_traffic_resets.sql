ALTER TABLE service_plans
    DROP CONSTRAINT ck_service_plans_package_fields;

ALTER TABLE service_plans
    ADD CONSTRAINT ck_service_plans_package_fields
        CHECK (
            plan_type = 'TRAFFIC_PACKAGE'
            OR purchase_limit_per_user IS NULL
        );
