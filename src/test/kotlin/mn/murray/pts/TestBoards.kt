package mn.murray.pts

import mn.murray.pts.domain.DEFAULT_PROJECT_BUCKETS
import mn.murray.pts.repository.ProjectBucketRepository
import mn.murray.pts.repository.TypeDefaultBucketRepository

/**
 * The three phases, written straight onto a project's board.
 *
 * Stage 100 stopped `ProjectService.create` handing every new board Engineering / Procurement / Production, so a
 * test that inserts a project row directly (rather than going through the create route) and then renders or files
 * cards against it has to write those lanes itself. [DEFAULT_PROJECT_BUCKETS] stays as the legacy trio — the shape
 * every board had before Stage 100 — which is exactly why it is the right thing for these fixtures: the pages
 * under test were written against a board of three phases and still print one.
 */
internal fun seedOpeningThree(projectId: Int) {
    DEFAULT_PROJECT_BUCKETS.forEach { ProjectBucketRepository.insert(projectId, it) }
}

/**
 * The same three, written into a category's Settings list instead — the way a category is given the old board shape
 * now that the create route reads nothing else. A board created afterwards opens with them.
 */
internal fun seedOpeningThreeFor(type: String) {
    DEFAULT_PROJECT_BUCKETS.forEach { TypeDefaultBucketRepository.insert(type, it) }
}
