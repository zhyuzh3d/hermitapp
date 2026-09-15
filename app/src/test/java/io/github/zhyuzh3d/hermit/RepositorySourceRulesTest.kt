package io.github.zhyuzh3d.hermit

import io.github.zhyuzh3d.hermit.install.RepositoryProvider
import io.github.zhyuzh3d.hermit.install.RepositorySourceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepositorySourceRulesTest {
    @Test fun parsesSupportedPublicRepositoryUrls() {
        val github = RepositorySourceRules.parse("https://github.com/owner/app.git?ref=release&path=web")!!
        assertEquals(RepositoryProvider.GITHUB, github.provider)
        assertEquals("owner", github.namespace)
        assertEquals("app", github.repository)
        assertEquals("release", github.ref)
        assertEquals("web", github.path)

        val gitlab = RepositorySourceRules.parse("https://gitlab.com/group/team/app/-/tree/main/dist")!!
        assertEquals(RepositoryProvider.GITLAB, gitlab.provider)
        assertEquals("group/team", gitlab.namespace)
        assertEquals("dist", gitlab.path)

        assertEquals(RepositoryProvider.GITEE, RepositorySourceRules.parse("https://gitee.com/owner/app")!!.provider)
        assertNull(RepositorySourceRules.parse("http://github.com/owner/app"))
        assertNull(RepositorySourceRules.parse("https://example.com/owner/app.git"))
    }

    @Test fun offersOnlyCommonDirectoriesThatExistPlusTheRepositoryRoot() {
        val options = RepositorySourceRules.directoryChoices(listOf(
            "README.md", "src/main.js", "dist/index.html", "dist/app.js", "release/app.zip", "private/index.html"
        ))
        assertEquals(listOf("dist", "release", ""), options.map { it.path })
        assertEquals("全部文件", options.last().label)
    }
}
