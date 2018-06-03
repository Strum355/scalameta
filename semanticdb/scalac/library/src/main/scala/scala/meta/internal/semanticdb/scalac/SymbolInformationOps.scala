package scala.meta.internal.semanticdb.scalac

import scala.{meta => m}
import scala.reflect.internal.{Flags => gf}
import scala.meta.internal.{semanticdb3 => s}
import scala.meta.internal.semanticdb3.Accessibility.{Tag => a}
import scala.meta.internal.semanticdb3.SymbolInformation.{Property => p}
import scala.meta.internal.semanticdb3.SymbolInformation.{Kind => k}
import scala.meta.internal.semanticdb3.Type.{Tag => t}

trait SymbolInformationOps { self: SemanticdbOps =>
  import g._

  implicit class XtensionGSymbolMSymbolInformation(gsym0: g.Symbol) {
    private val gsym: g.Symbol = {
      if (gsym0.isJavaClass) gsym0.companionClass
      else if (gsym0.isModuleClass) gsym0.asClass.module
      else if (gsym0.isTypeSkolem) gsym0.deSkolemize
      else gsym0
    }

    private def kind: s.SymbolInformation.Kind = {
      gsym match {
        case _ if gsym.isSelfParameter =>
          k.SELF_PARAMETER
        case gsym: MethodSymbol =>
          if (gsym.isConstructor) {
            k.CONSTRUCTOR
          } else {
            if (gsym.isGetter && gsym.isLazy && !gsym.isClass) {
              if (gsym.isLocalToBlock) k.LOCAL
              else k.METHOD
            } else if (gsym.isMacro) {
              k.MACRO
            } else {
              k.METHOD
            }
          }
        case gsym: ModuleSymbol =>
          if (gsym.hasPackageFlag) k.PACKAGE
          else if (gsym.isPackageObject) k.PACKAGE_OBJECT
          else k.OBJECT
        case gsym: TermSymbol =>
          if (gsym.isParameter) k.PARAMETER
          else if (gsym.isLocalToBlock) k.LOCAL
          else if (gsym.isJavaDefined || gsym.hasJavaEnumFlag) k.FIELD
          else k.METHOD
        case gsym: ClassSymbol =>
          if (gsym.isTrait && gsym.hasFlag(gf.JAVA)) k.INTERFACE
          else if (gsym.isTrait) k.TRAIT
          else k.CLASS
        case gsym: TypeSymbol =>
          if (gsym.isParameter) k.TYPE_PARAMETER
          else k.TYPE
        case NoSymbol =>
          k.UNKNOWN_KIND
        case _ =>
          sys.error(s"unsupported symbol $gsym")
      }
    }

    def language: s.Language =
      if (gsym.hasPackageFlag) s.Language.SCALA
      else if (gsym.hasFlag(gf.JAVA)) s.Language.JAVA
      else s.Language.SCALA

    private[meta] def properties: Int = {
      val kind = this.kind
      var flags = 0
      def flip(prop: s.SymbolInformation.Property): Unit =
        flags |= prop.value
      def isAbstractClass =
        gsym.isClass && gsym.isAbstract && !gsym.isTrait && !gsym.hasFlag(gf.JAVA_ENUM)
      def isAbstractMethod = gsym.isMethod && gsym.isDeferred
      def isAbstractType = gsym.isType && !gsym.isParameter && gsym.isDeferred
      def isObject = gsym.isModule && !gsym.hasFlag(gf.PACKAGE)
      if (gsym.hasFlag(gf.PACKAGE)) {
        ()
      } else if (gsym.hasFlag(gf.JAVA)) {
        if (isAbstractClass || kind.isInterface || isAbstractMethod) flip(p.ABSTRACT)
        if (gsym.hasFlag(gf.FINAL) || gsym.hasFlag(gf.JAVA_ENUM)) flip(p.FINAL)
        if (gsym.hasFlag(gf.JAVA_ENUM)) flip(p.ENUM)
        if (gsym.hasFlag(gf.STATIC)) flip(p.STATIC)
      } else {
        if (isAbstractClass || isAbstractMethod || isAbstractType) flip(p.ABSTRACT)
        if (gsym.hasFlag(gf.FINAL) || isObject) flip(p.FINAL)
        if (gsym.hasFlag(gf.SEALED)) flip(p.SEALED)
        if (gsym.hasFlag(gf.IMPLICIT)) flip(p.IMPLICIT)
        if (gsym.hasFlag(gf.LAZY)) flip(p.LAZY)
        if (gsym.hasFlag(gf.CASE) && (gsym.isClass || gsym.isModule)) flip(p.CASE)
        if (gsym.isType && gsym.hasFlag(gf.CONTRAVARIANT)) flip(p.CONTRAVARIANT)
        if (gsym.isType && gsym.hasFlag(gf.COVARIANT)) flip(p.COVARIANT)
        if (kind.isLocal || gsym.isUsefulField) {
          if (gsym.isMutable) flip(p.VAR)
          else if (gsym.isVal) flip(p.VAL)
          else ()
        }
        if (gsym.isGetter || gsym.isSetter) {
          if (gsym.isStable) flip(p.VAL)
          else flip(p.VAR)
        }
        if (gsym.isParameter && gsym.owner.isPrimaryConstructor) {
          val gaccessor = gsym.owner.owner.info.decl(gsym.name)
          if (gaccessor != g.NoSymbol && !gaccessor.isStable) flip(p.VAR)
          else if (gaccessor != g.NoSymbol && gaccessor.isMethod) flip(p.VAL)
          else ()
        }
        if (gsym.isPrimaryConstructor) flip(p.PRIMARY)
      }
      flags
    }

    private def name: String = {
      if (gsym.isPackageObject || gsym.isPackageObjectClass) {
        gsym.owner.name.toSemantic
      } else {
        gsym.name.toSemantic
      }
    }

    private def tpe: Option[s.Type] = {
      if (gsym.hasPackageFlag) {
        None
      } else {
        val ginfo = {
          if (gsym.hasFlag(gf.JAVA_ENUM) && gsym.isStatic) {
            gsym.info.widen
          } else if (gsym.isAliasType) {
            def preprocess(info: g.Type): g.Type = {
              info match {
                case g.PolyType(tparams, tpe) => g.PolyType(tparams, preprocess(tpe))
                case tpe => g.TypeBounds(tpe, tpe)
              }
            }
            preprocess(gsym.info)
          } else if (gsym.isModule) {
            gsym.moduleClass.info
          } else {
            gsym.info
          }
        }
        if (gsym.isConstructor) {
          ginfo.toSemantic.map(_.update(_.methodType.optionalReturnType := None))
        } else if (gsym.isScalacField) {
          val stag = t.METHOD_TYPE
          val sparamss = Nil
          val sret = ginfo.toSemantic
          Some(s.Type(tag = stag, methodType = Some(s.MethodType(Nil, sparamss, sret))))
        } else {
          ginfo.toSemantic
        }
      }
    }

    private def anns: List[s.Annotation] = {
      val ganns = gsym.annotations.filter { gann =>
        gann.atp.typeSymbol != definitions.MacroImplAnnotation
      }
      ganns.map(_.toSemantic)
    }

    // FIXME: https://github.com/scalameta/scalameta/issues/1325
    private def acc: s.Accessibility = {
      if (gsym.hasFlag(gf.SYNTHETIC) && gsym.hasFlag(gf.ARTIFACT)) {
        // NOTE: some sick artifact vals produced by mkPatDef can be
        // private to method (whatever that means), so here we just ignore them.
        s.Accessibility(a.PUBLIC)
      } else {
        if (gsym.privateWithin == NoSymbol) {
          if (gsym.isPrivateThis) s.Accessibility(a.PRIVATE_THIS)
          else if (gsym.isPrivate) s.Accessibility(a.PRIVATE)
          else if (gsym.isProtectedThis) s.Accessibility(a.PROTECTED_THIS)
          else if (gsym.isProtected) s.Accessibility(a.PROTECTED)
          else s.Accessibility(a.PUBLIC)
        } else {
          val ssym = gsym.privateWithin.toSemantic.syntax
          if (gsym.isProtected) s.Accessibility(a.PROTECTED_WITHIN, ssym)
          else s.Accessibility(a.PRIVATE_WITHIN, ssym)
        }
      }
    }

    def toSymbolInformation: s.SymbolInformation = {
      s.SymbolInformation(
        symbol = gsym.toSemantic.syntax,
        language = language,
        kind = kind,
        accessibility = Some(acc),
        properties = properties,
        name = name,
        tpe = tpe,
        annotations = anns
      )
    }
  }
}
