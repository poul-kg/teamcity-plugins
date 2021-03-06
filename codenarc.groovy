
ruleset {

    description 'TeamCity plugins CodeNarc RuleSet'

    ruleset( "http://codenarc.sourceforge.net/StarterRuleSet-AllRulesByCategory.groovy.txt" ) {

        DuplicateNumberLiteral          ( enabled : false )
        DuplicateStringLiteral          ( enabled : false )
        BracesForClass                  ( enabled : false )
        BracesForMethod                 ( enabled : false )
        BracesForIfElse                 ( enabled : false )
        BracesForForLoop                ( enabled : false )
        BracesForTryCatchFinally        ( enabled : false )
        JavaIoPackageAccess             ( enabled : false )
        VariableName                    ( enabled : false )
        FactoryMethodName               ( enabled : false )
        SpaceBeforeOpeningBrace         ( enabled : false )
        SpaceBeforeClosingBrace         ( enabled : false )
        SpaceAfterIf                    ( enabled : false )
        ThisReferenceEscapesConstructor ( enabled : false )
        LineLength                      ( length  : 160   )
        MethodName                      ( regex   : /[a-z][\w\s'\(\)]*/ ) // Spock method names
    }
}
