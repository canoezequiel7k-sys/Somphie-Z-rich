package com.somphie.identity.infrastructure

import com.somphie.identity.application.IdentityCrypto
import com.somphie.identity.application.IdentityRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("dev", "demo")
class IdentityDemoSeeder(
    private val repository: IdentityRepository,
    private val crypto: IdentityCrypto,
    @Value("\${somphie.identity.demo-password:demo-only-password}")
    private val demoPassword: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        require(demoPassword.length >= 10) { "DEMO_STAFF_PASSWORD must contain at least 10 characters" }
        repository.seedDemoUsers(crypto.encodePassword(demoPassword))
    }
}
