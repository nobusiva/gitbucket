package app

import util.{CollaboratorsAuthenticator, FileUtil, JGitUtil, ReferrerAuthenticator}
import util.Directory._
import service._
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import util.JGitUtil.{DiffInfo, CommitInfo}
import scala.collection.mutable.ArrayBuffer
import org.eclipse.jgit.api.Git
import jp.sf.amateras.scalatra.forms._
import util.JGitUtil.DiffInfo
import scala.Some
import util.JGitUtil.CommitInfo

class PullRequestsController extends PullRequestsControllerBase
  with RepositoryService with AccountService with IssuesService with PullRequestService with MilestonesService with ActivityService
  with ReferrerAuthenticator with CollaboratorsAuthenticator

trait PullRequestsControllerBase extends ControllerBase {
  self: RepositoryService with IssuesService with MilestonesService with ActivityService with PullRequestService
    with ReferrerAuthenticator with CollaboratorsAuthenticator =>

  val form = mapping(
    "title"           -> trim(label("Title"  , text(required, maxlength(100)))),
    "content"         -> trim(label("Content", optional(text()))),
    "branch"          -> trim(text(required, maxlength(100))),
    "requestUserName" -> trim(text(required, maxlength(100))),
    "requestBranch"   -> trim(text(required, maxlength(100)))
  )(PullRequestForm.apply)

  case class PullRequestForm(title: String, content: Option[String], branch: String,
                             requestUserName: String, requestBranch: String)

  get("/:owner/:repository/pulls")(referrersOnly { repository =>
    pulls.html.list(repository)
  })

  get("/:owner/:repository/pulls/:id")(referrersOnly { repository =>
    val owner   = repository.owner
    val name    = repository.name
    val issueId = params("id").toInt

    getPullRequest(owner, name, issueId) map { case(issue, pullreq) =>
      pulls.html.pullreq(
        issue, pullreq,
        getComments(owner, name, issueId.toInt),
        (getCollaborators(owner, name) :+ owner).sorted,
        getMilestones(owner, name),
        hasWritePermission(owner, name, context.loginAccount),
        repository)
    } getOrElse NotFound
  })

  get("/:owner/:repository/pulls/:id/commits")(referrersOnly { repository =>
    val owner   = repository.owner
    val name    = repository.name
    val issueId = params("id").toInt

    getPullRequest(owner, name, issueId) map { case(issue, pullreq) =>
      pulls.html.commits(
        issue, pullreq,
        getCompareInfo(owner, name, pullreq.branch, pullreq.requestUserName, pullreq.requestRepositoryName, pullreq.requestBranch)._1,
        hasWritePermission(owner, name, context.loginAccount),
        repository)
    } getOrElse NotFound
  })

  get("/:owner/:repository/pulls/:id/files")(referrersOnly { repository =>
    val owner   = repository.owner
    val name    = repository.name
    val issueId = params("id").toInt

    getPullRequest(owner, name, issueId) map { case(issue, pullreq) =>
      JGitUtil.withGit(getRepositoryDir(owner, name)){ git =>
        val newId = git.getRepository.resolve(pullreq.requestBranch)

        pulls.html.files(
          issue, pullreq,
          getCompareInfo(owner, name, pullreq.branch, pullreq.requestUserName, pullreq.requestRepositoryName, pullreq.requestBranch)._2,
          newId.getName,
          hasWritePermission(owner, name, context.loginAccount),
          repository)
      }
    } getOrElse NotFound
  })


  post("/:owner/:repository/pulls/:id/merge")(collaboratorsOnly { repository =>
    // TODO Not implemented yet.
  })

  // TODO Replace correct authenticator
  get("/:owner/:repository/pulls/compare")(collaboratorsOnly { newRepo =>
    (newRepo.repository.originUserName, newRepo.repository.originRepositoryName) match {
      case (None,_)|(_, None) => NotFound // TODO BadRequest?
      case (Some(originUserName), Some(originRepositoryName)) => {
        getRepository(originUserName, originRepositoryName, baseUrl).map { oldRepo =>
          withGit(
            getRepositoryDir(originUserName, originRepositoryName),
            getRepositoryDir(params("owner"), params("repository"))
          ){ (oldGit, newGit) =>
            val oldBranch = JGitUtil.getDefaultBranch(oldGit, oldRepo).get._2
            val newBranch = JGitUtil.getDefaultBranch(newGit, newRepo).get._2

            redirect(s"${context.path}/${newRepo.owner}/${newRepo.name}/pulls/compare/${originUserName}:${oldBranch}...${newBranch}")
          }
        } getOrElse NotFound
      }
    }
  })

  // TODO Replace correct authenticator
  get("/:owner/:repository/pulls/compare/*:*...*")(collaboratorsOnly { repository =>
    if(repository.repository.originUserName.isEmpty || repository.repository.originRepositoryName.isEmpty){
      NotFound // TODO BadRequest?
    } else {
      getRepository(
        repository.repository.originUserName.get,
        repository.repository.originRepositoryName.get, baseUrl
      ).map{ originRepository =>
        val Seq(origin, originId, forkedId) = multiParams("splat")
        val userName       = params("owner")
        val repositoryName = params("repository")

        JGitUtil.withGit(getRepositoryDir(userName, repositoryName)){ git =>
          val newId = git.getRepository.resolve(forkedId)

          val pullreq = getCompareInfo(
            origin, repository.repository.originRepositoryName.get, originId,
            params("owner"), params("repository"), forkedId)

          pulls.html.compare(pullreq._1, pullreq._2, origin, originId, forkedId, newId.getName, repository, originRepository)
        }
      } getOrElse NotFound
    }
  })

  post("/:owner/:repository/pulls/new", form)(referrersOnly { (form, repository) =>
    val loginUserName = context.loginAccount.get.userName

    val issueId = createIssue(
      repository.owner,
      repository.name,
      loginUserName,
      form.title,
      form.content,
      None, None)

    createPullRequest(
      repository.owner,
      repository.name,
      issueId,
      form.branch,
      form.requestUserName,
      repository.name,
      form.requestBranch)

    recordPullRequestActivity(repository.owner, repository.name, loginUserName, issueId, form.title)

    redirect(s"/${repository.owner}/${repository.name}/pulls/${issueId}")
  })

  private def withGit[T](oldDir: java.io.File, newDir: java.io.File)(action: (Git, Git) => T): T = {
    val oldGit = Git.open(oldDir)
    val newGit = Git.open(newDir)
    try {
      action(oldGit, newGit)
    } finally {
      oldGit.getRepository.close
      newGit.getRepository.close
    }
  }

  /**
   * Returns the commits and diffs between specified repository and revision.
   */
  private def getCompareInfo(userName: String, repositoryName: String, branch: String,
      requestUserName: String, requestRepositoryName: String, requestBranch: String): (Seq[Seq[CommitInfo]], Seq[DiffInfo]) = {
    withGit(
      getRepositoryDir(userName, repositoryName),
      getRepositoryDir(requestUserName, requestRepositoryName)
    ){ (oldGit, newGit) =>
      val oldReader = oldGit.getRepository.newObjectReader
      val oldTreeIter = new CanonicalTreeParser
      oldTreeIter.reset(oldReader, oldGit.getRepository.resolve(s"${branch}^{tree}"))

      val newReader = newGit.getRepository.newObjectReader
      val newTreeIter = new CanonicalTreeParser
      newTreeIter.reset(newReader, newGit.getRepository.resolve(s"${requestBranch}^{tree}"))

      import scala.collection.JavaConverters._
      import util.Implicits._

      val oldId = oldGit.getRepository.resolve(branch)
      val newId = newGit.getRepository.resolve(requestBranch)
      val i = newGit.log.addRange(oldId, newId).call.iterator

      val commits = new ArrayBuffer[CommitInfo]
      while(i.hasNext){
        val revCommit = i.next
        commits += new CommitInfo(revCommit)
      }

      val diffs = newGit.diff.setOldTree(oldTreeIter).setNewTree(newTreeIter).call.asScala.map { diff =>
        if(FileUtil.isImage(diff.getOldPath) || FileUtil.isImage(diff.getNewPath)){
          DiffInfo(diff.getChangeType, diff.getOldPath, diff.getNewPath, None, None)
        } else {
          DiffInfo(diff.getChangeType, diff.getOldPath, diff.getNewPath,
            JGitUtil.getContent(oldGit, diff.getOldId.toObjectId, false).filter(FileUtil.isText).map(new String(_, "UTF-8")),
            JGitUtil.getContent(newGit, diff.getNewId.toObjectId, false).filter(FileUtil.isText).map(new String(_, "UTF-8")))
        }
      }

      (commits.toList.splitWith{ (commit1, commit2) =>
        view.helpers.date(commit1.time) == view.helpers.date(commit2.time)
      }, diffs.toSeq)
    }
  }

}
