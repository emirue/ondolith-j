package dev.ondolithj.ops;

import org.springframework.data.jpa.repository.JpaRepository;

interface SettingRepository extends JpaRepository<Setting, String> {
}
