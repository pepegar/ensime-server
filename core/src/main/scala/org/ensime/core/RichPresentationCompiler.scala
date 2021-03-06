// Copyright: 2010 - 2017 https://github.com/ensime/ensime-server/graphs
// License: http://www.gnu.org/licenses/gpl-3.0.en.html
/*
 * This file contains derivative works that require the following
 * header to be displayed:
 *
 * Copyright 2002-2011 EPFL.
 * All rights reserved.
 *
 * Permission to use, copy, modify, and distribute this software in
 * source or binary form for any purpose with or without fee is hereby
 * granted, provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer.
 *    2. Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *    3. Neither the name of the EPFL nor the names of its
 *       contributors may be used to endorse or promote products
 *       derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE REGENTS OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */
package org.ensime.core

import java.nio.charset.Charset
import java.nio.file.{ Path, Paths }
import java.net.URI

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.mutable
import scala.collection.immutable.{ Set => SCISet }
import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.internal.util.{ BatchSourceFile, RangePosition, SourceFile }
import scala.reflect.io.{ PlainFile, VirtualFile }
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.{ CompilerControl, Global }
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.reporters.Reporter
import scala.tools.nsc.util._
import scala.tools.refactoring.analysis.GlobalIndexes
import akka.actor.ActorRef
import org.ensime.api._
import org.ensime.indexer._
import org.ensime.model._
import org.ensime.util.ensimefile._
import org.ensime.util.file._
import org.ensime.util.sourcefile._
import org.ensime.vfs._
import org.slf4j.LoggerFactory

trait RichCompilerControl extends CompilerControl with RefactoringControl with CompletionControl with DocFinding {
  self: RichPresentationCompiler =>

  implicit def charset: Charset = Charset.forName(settings.encoding.value)

  def askOption[A](op: => A): Option[A] =
    try {
      Some(ask(() => op))
    } catch {
      case fi: FailedInterrupt =>
        fi.getCause match {
          case e: InterruptedException =>
            Thread.currentThread().interrupt()
            logger.error("interrupted exception in askOption", e)
            None
          case e =>
            logger.error("Error during askOption", e)
            None
        }
      case e: Throwable =>
        logger.error("Error during askOption", e)
        None
    }

  def askDocSignatureAtPoint(p: Position): Option[DocSigPair] =
    askOption {
      symbolAt(p).orElse(typeAt(p).flatMap(_.typeSymbol.toOption)).flatMap(docSignature(_, Some(p)))
    }.flatten

  def askDocSignatureForSymbol(typeFullName: String, memberName: Option[String],
    signatureString: Option[String]): Option[DocSigPair] =
    askOption {
      val sym = symbolMemberByName(typeFullName, memberName, signatureString)
      docSignature(sym, None)
    }.flatten

  ////////////////////////////////////////////////////////////////////////////////
  // exposed for testing
  def askSymbolFqn(p: Position): Option[FullyQualifiedName] =
    askOption(symbolAt(p).map(toFqn)).flatten
  def askSymbolFqn(s: Symbol): Option[FullyQualifiedName] = askOption(toFqn(s))
  def askTypeFqn(p: Position): Option[FullyQualifiedName] =
    askOption(typeAt(p).map { tpe => toFqn(tpe.typeSymbol) }).flatten
  def askSymbolByScalaName(name: String, declaredAs: Option[DeclaredAs] = None): Option[Symbol] =
    askOption(toSymbol(name, declaredAs))
  def askSymbolByFqn(fqn: FullyQualifiedName): Option[Symbol] =
    askOption(toSymbol(fqn))
  def askSymbolAt(p: Position): Option[Symbol] =
    askOption(symbolAt(p)).flatten
  def askTypeSymbolAt(p: Position): Option[Symbol] =
    askOption(typeAt(p).map { tpe => tpe.typeSymbol }).flatten
  ////////////////////////////////////////////////////////////////////////////////

  def askSymbolInfoAt(p: Position): Option[SymbolInfo] =
    askOption(symbolAt(p).map(SymbolInfo(_))).flatten

  def askSymbolByName(fqn: String, memberName: Option[String], signatureString: Option[String]): Option[SymbolInfo] =
    askOption {
      SymbolInfo(symbolMemberByName(fqn, memberName, signatureString))
    }

  def askTypeInfoAt(p: Position): Option[TypeInfo] =
    askOption(typeAt(p).map(TypeInfo(_, PosNeededYes))).flatten

  def askTypeInfoByName(name: String): Option[TypeInfo] =
    askOption(TypeInfo(toSymbol(name).tpe, PosNeededYes))

  def askTypeInfoByNameAt(name: String, p: Position): Option[TypeInfo] = {
    val nameSegs = name.split("\\.")
    val firstName: String = nameSegs.head
    val x = new Response[List[Member]]()
    askScopeCompletion(p, x)
    (for (
      members <- x.get.left.toOption;
      infos <- askOption {
        val roots = filterMembersByPrefix(
          members, firstName, matchEntire = true, caseSens = true
        ).map { _.sym }
        val restOfPath = nameSegs.drop(1).mkString(".")
        val syms = roots.map { toSymbol(restOfPath, None, _) }
        syms.find(_.tpe != NoType).map { sym => TypeInfo(sym.tpe) }
      }
    ) yield infos).flatten
  }

  def askPackageByPath(path: String): Option[PackageInfo] =
    askOption(PackageInfo.fromPath(path))

  def askReloadFile(f: SourceFile): Unit = {
    askReloadFiles(List(f))
  }

  def askReloadFiles(files: Iterable[SourceFile]): Either[Unit, Throwable] = {
    val x = new Response[Unit]()
    askReload(files.toList, x)
    x.get
  }

  def askLoadedTyped(f: SourceFile): Either[Tree, Throwable] = {
    val x = new Response[Tree]()
    askLoadedTyped(f, true, x)
    x.get
  }

  def askUnloadFiles(sources: List[SourceFileInfo], remove: Boolean): Unit = {
    val files = sources.map(createSourceFile)
    askOption(unloadFiles(files, remove)).get
  }

  def loadedFiles: List[SourceFile] = activeUnits().map(_.source)

  def askInspectTypeAt(p: Position): Option[TypeInspectInfo] =
    askOption(inspectTypeAt(p)).flatten

  def askInspectTypeByName(name: String): Option[TypeInspectInfo] =
    askOption(inspectType(toSymbol(name).tpe))

  def askCompletePackageMember(path: String, prefix: String): List[CompletionInfo] =
    askOption(completePackageMember(path, prefix)).getOrElse(List.empty)

  def askCompletionsAt(p: Position, maxResults: Int, caseSens: Boolean): Future[CompletionInfoList] =
    completionsAt(p, maxResults, caseSens)

  def askReloadAndTypeFiles(files: Iterable[SourceFile]) =
    askOption(reloadAndTypeFiles(files))

  def askUsesOfSymAtPos(pos: Position)(implicit ec: ExecutionContext): Future[List[RangePosition]] = {
    askLoadedTyped(pos.source)
    val symbol = askSymbolAt(pos)
    symbol match {
      case None => Future.successful(Nil)
      case Some(sym) =>
        val source = pos.source
        val loadedFiles = loadUsesOfSym(sym)
        loadedFiles.map { lfs =>
          val files = lfs.map(_.file) + source.file.file.toPath
          askUsesOfSym(sym, files)
        }
    }
  }

  def askUsesOfSym(sym: Symbol, files: SCISet[Path]): List[RangePosition] =
    askOption(usesOfSymbol(sym.pos, files).toList).getOrElse(List.empty)

  protected def withExistingScalaFiles(
    files: SCISet[SourceFileInfo]
  )(
    f: List[SourceFile] => RpcResponse
  ): RpcResponse = {
    val (existing, missingFiles) = files.partition(_.exists())
    if (missingFiles.nonEmpty) {
      val missingFilePaths = missingFiles.map { f => "\"" + f.file + "\"" }.mkString(",")
      EnsimeServerError(s"file(s): $missingFilePaths do not exist")
    } else {
      val scalas = existing.collect { case sfi: SourceFileInfo if sfi.file.isScala => sfi }
      val sourceFiles: List[SourceFile] = scalas.map(createSourceFile)(collection.breakOut)
      f(sourceFiles)
    }
  }

  def handleReloadFiles(files: SCISet[SourceFileInfo]): RpcResponse = {
    withExistingScalaFiles(files) { scalas =>
      if (scalas.nonEmpty) {
        askReloadFiles(scalas)
        askNotifyWhenReady()
      }
      VoidResponse
    }
  }

  def handleReloadAndRetypeFiles(files: SCISet[SourceFileInfo]): RpcResponse = {
    withExistingScalaFiles(files) { scalas =>
      if (scalas.nonEmpty) {
        askReloadFiles(scalas)
        scalas.foreach(askLoadedTyped)
        askNotifyWhenReady()
      }
      VoidResponse
    }
  }

  import org.ensime.util.file.File
  def loadUsesOfSym(sym: Symbol)(implicit ec: ExecutionContext): Future[SCISet[RawFile]] = {
    val files = usesOfSym(sym)
    files.map { rfs =>
      val sfis = rfs.map(rf => SourceFileInfo(rf))
      handleReloadAndRetypeFiles(sfis)
      rfs
    }
  }

  def usesOfSym(sym: Symbol)(implicit ec: ExecutionContext): Future[SCISet[RawFile]] = {
    val noReverseLookups = search.noReverseLookups
    if (noReverseLookups) {
      Future.successful(Set.empty)
    } else {
      val symbolFqn = askSymbolFqn(sym)
      symbolFqn.fold(Future.successful(Set.empty[RawFile])) { fqn =>
        val usages = search.findUsages(fqn.fqnString)
        usages.map { usages =>
          val uniqueFiles: SCISet[RawFile] = usages.flatMap { u =>
            val source = u.source
            source.map(s => RawFile(Paths.get(new URI(s))))
          }(collection.breakOut)
          uniqueFiles + RawFile(sym.sourceFile.file.toPath)
        }
      }
    }
  }

  // force the full path of Set because nsc appears to have a conflicting Set....
  def askSymbolDesignationsInRegion(p: RangePosition, tpes: List[SourceSymbol]): SymbolDesignations =
    askOption(
      new SemanticHighlighting(this).symbolDesignationsInRegion(p, tpes)
    ).getOrElse(SymbolDesignations(RawFile(new File(".").toPath), List.empty))

  def askImplicitInfoInRegion(p: Position): ImplicitInfos =
    ImplicitInfos(
      askOption(
        new ImplicitAnalyzer(this).implicitDetails(p)
      ).getOrElse(List.empty)
    )

  def askNotifyWhenReady(): Unit = ask(setNotifyWhenReady _)

  // WARNING: be really careful when creating BatchSourceFiles. there
  // are multiple constructors which do weird things, best to be very
  // explicit about what we're doing and only use the primary
  // constructor. Note that scalac appears to have a bug in it whereby
  // it is unable to tell that a VirtualFile (i.e. in-memory) and a
  // non VirtualFile backed BatchSourceFile are actually referring to
  // the same compilation unit. see
  // https://github.com/ensime/ensime-server/issues/1160
  def createSourceFile(file: EnsimeFile): BatchSourceFile =
    createSourceFile(SourceFileInfo(file))
  def createSourceFile(file: File): BatchSourceFile =
    createSourceFile(EnsimeFile(file))
  def createSourceFile(path: String): BatchSourceFile =
    createSourceFile(EnsimeFile(path))
  def createSourceFile(file: AbstractFile): BatchSourceFile =
    createSourceFile(file.path)
  def createSourceFile(file: SourceFileInfo): BatchSourceFile = file match {
    case SourceFileInfo(rf @ RawFile(f), None, None, _) => new BatchSourceFile(
      new PlainFile(f.toFile), rf.readStringDirect().toCharArray
    )
    case SourceFileInfo(ac @ ArchiveFile(archive, entry), None, None, _) =>
      new BatchSourceFile(
        new VirtualFile(ac.fullPath), ac.readStringDirect().toCharArray
      )
    case SourceFileInfo(rf @ RawFile(f), Some(contents), None, _) =>
      new BatchSourceFile(new PlainFile(f.toFile), contents.toCharArray)
    case SourceFileInfo(ac @ ArchiveFile(a, e), Some(contents), None, _) => new BatchSourceFile(
      new VirtualFile(ac.fullPath), contents.toCharArray
    )
    case SourceFileInfo(rf @ RawFile(f), None, Some(contentsIn), _) =>
      new BatchSourceFile(new PlainFile(f.toFile), contentsIn.readString()(charset).toCharArray)
    case SourceFileInfo(ac @ ArchiveFile(a, e), None, Some(contentsIn), _) => new BatchSourceFile(
      new VirtualFile(ac.fullPath), contentsIn.readString()(charset).toCharArray
    )
    case _ => throw new IllegalArgumentException(s"Invalid contents of SourceFileInfo parameter: $file.")
  }

  def askLinkPos(sym: Symbol, path: EnsimeFile): Option[Position] =
    askOption(linkPos(sym, createSourceFile(path)))

  def askStructure(fileInfo: SourceFile): List[StructureViewMember] =
    askOption(structureView(fileInfo))
      .getOrElse(List.empty)

  def askRaw(any: Any): String =
    showRaw(any, printTypes = true, printIds = false, printKinds = true, printMirrors = true)

  /**
   * Returns the smallest `Tree`, which position `properlyIncludes` `p`
   */
  def askEnclosingTreePosition(p: Position): Position =
    new PositionLocator(this).enclosingTreePosition(p)
}

class RichPresentationCompiler(
  val config: EnsimeConfig,
  override val settings: Settings,
  val richReporter: Reporter,
  val parent: ActorRef,
  val indexer: ActorRef,
  val search: SearchService
)(
  implicit
  val vfs: EnsimeVFS,
  val ec: ExecutionContext
) extends Global(settings, richReporter)
    with ModelBuilders with RichCompilerControl
    with RefactoringImpl with Completion with Helpers
    with PresentationCompilerBackCompat with PositionBackCompat
    with StructureViewBuilder
    with SymbolToFqn
    with FqnToSymbol
    with TypeToScalaName {

  val logger = LoggerFactory.getLogger(this.getClass)

  private val symsByFile = new mutable.HashMap[AbstractFile, mutable.LinkedHashSet[Symbol]] {
    override def default(k: AbstractFile) = {
      val v = new mutable.LinkedHashSet[Symbol]
      put(k, v)
      v
    }
  }

  def activeUnits(): List[CompilationUnit] = {
    val invalidSet = toBeRemoved.synchronized { toBeRemoved.toSet }
    unitOfFile.filter { kv => !invalidSet.contains(kv._1) }.values.toList
  }

  /** Called from typechecker every time a top-level class or object is entered.*/
  override def registerTopLevelSym(sym: Symbol): Unit = {
    super.registerTopLevelSym(sym)
    symsByFile(sym.sourceFile) += sym
  }

  def unloadFiles(files: List[SourceFile], remove: Boolean): Unit = {
    files.foreach { f =>
      removeUnitOf(f)

      // more aggressive, e.g. if the file was deleted/removed by the user
      if (remove) {
        val af = f.file
        val syms = symsByFile(af)
        for (s <- syms) {
          s.owner.info.decls unlink s
        }
        symsByFile.remove(af)
        unitOfFile.remove(af)
      }
    }
  }

  private def typePublicMembers(tpe: Type): Iterable[TypeMember] = {
    val members = new mutable.LinkedHashMap[Symbol, TypeMember]
    def addTypeMember(sym: Symbol, inherited: Boolean, viaView: Symbol): Unit = {
      try {
        val m = TypeMember(
          sym,
          sym.tpe,
          sym.isPublic,
          inherited,
          viaView
        )
        members(sym) = m
      } catch {
        case e: Throwable =>
          logger.error("Error: Omitting member " + sym + ": " + e)
      }
    }
    for (sym <- tpe.decls) {
      addTypeMember(sym, inherited = false, NoSymbol)
    }
    for (sym <- tpe.members) {
      addTypeMember(sym, inherited = true, NoSymbol)
    }
    members.values
  }

  protected def getMembersForTypeAt(tpe: Type, p: Position): Iterable[Member] = {
    if (isNoParamArrowType(tpe)) {
      typePublicMembers(typeOrArrowTypeResult(tpe))
    } else {
      val members: Iterable[Member] = try {
        wrapTypeMembers(p)
      } catch {
        case e: Throwable =>
          logger.error("Error retrieving type members:", e)
          List.empty
      }
      // Remove duplicates
      // Filter out synthetic things
      val bySym = new mutable.LinkedHashMap[Symbol, Member]
      for (m <- members ++ typePublicMembers(tpe)) {
        if (!m.sym.nameString.contains("$")) {
          bySym(m.sym) = m
        }
      }
      bySym.values
    }
  }

  protected def inspectType(tpe: Type): TypeInspectInfo = {
    val parents = tpe.parents
    TypeInspectInfo(
      TypeInfo(tpe, PosNeededAvail),
      prepareSortedInterfaceInfo(typePublicMembers(tpe.asInstanceOf[Type]), parents)
    )
  }

  protected def inspectTypeAt(p: Position): Option[TypeInspectInfo] = {
    typeAt(p).map(tpe => {
      val members = getMembersForTypeAt(tpe, p)
      val parents = tpe.parents
      val preparedMembers = prepareSortedInterfaceInfo(members, parents)
      TypeInspectInfo(
        TypeInfo(tpe, PosNeededAvail),
        preparedMembers
      )
    }).orElse {
      logger.error("ERROR: Failed to get any type information :(  ")
      None
    }
  }

  private def typeOfTree(t: Tree): Option[Type] = {
    val tree = t match {
      case Select(qualifier, name) if t.tpe == ErrorType =>
        qualifier
      case t: ImplDef if t.impl != null =>
        t.impl
      case t: ValOrDefDef if t.tpt != null =>
        t.tpt
      case t: ValOrDefDef if t.rhs != null =>
        t.rhs
      case otherTree =>
        otherTree
    }

    Option(tree.tpe)
  }

  protected def typeAt(p: Position): Option[Type] = {
    wrapTypedTreeAt(p) match {
      case Import(_, _) => symbolAt(p).map(_.tpe)
      case tree => typeOfTree(tree)
    }
  }

  protected def symbolMemberByName(
    name: String, member: Option[String], descriptor: Option[String]
  ): Symbol = {
    val clazz = ClassName.fromFqn(name)
    val fqn = (member, descriptor) match {
      case (Some(field), None) => FieldName(clazz, field)
      case (Some(method), Some(desc)) => MethodName(clazz, method, DescriptorParser.parse(desc))
      case _ => clazz
    }
    toSymbol(fqn)
  }

  protected def filterMembersByPrefix(members: List[Member], prefix: String,
    matchEntire: Boolean, caseSens: Boolean): List[Member] = members.filter { m =>
    val prefixUpper = prefix.toUpperCase
    val sym = m.sym
    val ns = sym.nameString
    (((matchEntire && ns == prefix) ||
      (!matchEntire && caseSens && ns.startsWith(prefix)) ||
      (!matchEntire && !caseSens && ns.toUpperCase.startsWith(prefixUpper)))
      && !sym.nameString.contains("$"))
  }

  private def noDefinitionFound(tree: Tree) = {
    logger.warn("No definition found. Please report to https://github.com/ensime/ensime-server/issues/492 what you expected for " + tree.getClass + ": " + showRaw(tree))
    Nil
  }

  protected def symbolAt(pos: Position): Option[Symbol] = {
    val tree = wrapTypedTreeAt(pos)
    val wannabes =
      tree match {
        case Import(expr, selectors) =>
          if (expr.pos.includes(pos)) {
            @annotation.tailrec
            def locate(p: Position, inExpr: Tree): Symbol = inExpr match {
              case Select(qualifier, name) =>
                if (qualifier.pos.includes(p)) locate(p, qualifier)
                else inExpr.symbol
              case tree => tree.symbol
            }
            List(locate(pos, expr))
          } else {
            selectors.filter(_.namePos <= pos.point).sortBy(_.namePos).lastOption map { sel =>
              val tpe = stabilizedType(expr)
              List(tpe.member(sel.name), tpe.member(sel.name.toTypeName))
            } getOrElse Nil
          }
        case Annotated(atp, _) =>
          List(atp.symbol)
        case ap @ Select(qualifier, nme.apply) =>
          // If we would like to give user choice if to go to method apply or value
          // like Eclipse is doing we would need to return:
          // List(qualifier.symbol, ap.symbol)
          List(qualifier.symbol)
        case st if st.symbol ne null =>
          List(st.symbol)
        case lit: Literal =>
          List(lit.tpe.typeSymbol)

        case _ =>
          noDefinitionFound(tree)
      }
    wannabes.find(_.exists)
  }

  protected def specificOwnerOfSymbolAt(pos: Position): Option[Symbol] = {
    val tree = wrapTypedTreeAt(pos)
    tree match {
      case tree @ Select(qualifier, name) =>
        qualifier match {
          case t: ApplyImplicitView => t.args.headOption.map(_.tpe.typeSymbol)
          case _ => Some(qualifier.tpe.typeSymbol)
        }
      case _ => None
    }
  }

  protected def linkPos(sym: Symbol, source: SourceFile): Position = {
    wrapLinkPos(sym, source)
  }

  protected def usesOfSymbol(pos: Position, files: collection.Set[Path]): Iterable[RangePosition] = {
    symbolAt(pos) match {
      case Some(s) =>
        class CompilerGlobalIndexes extends GlobalIndexes {
          val global = RichPresentationCompiler.this
          val sym = s.asInstanceOf[global.Symbol]
          val cuIndexes = this.global.unitOfFile.collect {
            case (file, unit) if search.noReverseLookups || files.contains(file.file.toPath) =>
              CompilationUnitIndex(unit.body)
          }
          val index = GlobalIndex(cuIndexes.toList)
          val result = index.occurences(sym).map { r =>
            r.pos match {
              case p: RangePosition => p
              case p =>
                new RangePosition(
                  p.source, p.point, p.point, p.point
                )
            }
          }
        }
        val gi = new CompilerGlobalIndexes
        gi.result
      case None => Nil
    }
  }

  private var notifyWhenReady = false

  override def isOutOfDate: Boolean = {
    if (notifyWhenReady && !super.isOutOfDate) {
      parent ! FullTypeCheckCompleteEvent
      notifyWhenReady = false
    }
    super.isOutOfDate
  }

  protected def setNotifyWhenReady(): Unit = {
    notifyWhenReady = true
  }

  protected def reloadAndTypeFiles(sources: Iterable[SourceFile]) = {
    wrapReloadSources(sources.toList)
    sources.foreach { s =>
      wrapTypedTree(s, forceReload = true)
    }
  }

  override def askShutdown(): Unit = {
    super.askShutdown()
  }

  /*
    * The following functions wrap up operations that interact with
    * the presentation compiler. The wrapping just helps with the
    * create response / compute / get result pattern.
    *
    * These units of work should return `Future[T]`.
    */
  def wrap[A](compute: Response[A] => Unit, handle: Throwable => A): A = {
    val result = new Response[A]
    compute(result)
    result.get.fold(o => o, handle)
  }

  def wrapReloadPosition(p: Position): Unit =
    wrapReloadSource(p.source)

  def wrapReloadSource(source: SourceFile): Unit =
    wrapReloadSources(List(source))

  def wrapReloadSources(sources: List[SourceFile]): Unit = {
    val superseded = scheduler.dequeueAll {
      case ri: ReloadItem if ri.sources == sources => Some(ri)
      case _ => None
    }
    superseded.foreach(_.response.set(()))
    wrap[Unit](r => ReloadItem(sources, r).apply(), _ => ())
  }

  def wrapTypeMembers(p: Position): List[Member] =
    wrap[List[Member]](r => AskTypeCompletionItem(p, r).apply(), _ => List.empty)

  def wrapTypedTree(source: SourceFile, forceReload: Boolean): Tree =
    wrap[Tree](r => AskTypeItem(source, forceReload, r).apply(), t => throw t)

  def wrapTypedTreeAt(position: Position): Tree =
    wrap[Tree](r => AskTypeAtItem(position, r).apply(), t => throw t)

  def wrapLinkPos(sym: Symbol, source: SourceFile): Position =
    wrap[Position](r => AskLinkPosItem(sym, source, r).apply(), t => throw t)

}
