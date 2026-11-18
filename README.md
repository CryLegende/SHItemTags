<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHItemTags

*Nametags enchantés pour renommer un item en couleur (remplace ItemTags, compatible avec ses tags)*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Nametags enchantés pour renommer un item en couleur (remplace ItemTags, compatible avec ses tags)

Utilisé sur : **Opale**.

## Fonctionnement

1. Clic droit avec le Nametag : un menu s'ouvre.
2. Le joueur pose l'item à renommer dans la case du centre. La vitre à droite devient **verte** (renommable) ou **rouge** (item interdit : `items-interdits`, Nametag).
3. Clic sur la vitre verte : l'item revient dans l'inventaire et un dialogue demande le nom (pré-rempli avec le nom actuel).
   - **Aperçu** montre l'item avec le nouveau nom et dit si le nom sera refusé (mot interdit, trop long), sans rien utiliser.
   - **Renommer** applique le nom et retire un Nametag, ou rouvre le dialogue avec la raison du refus.
   - **Annuler** (ou Échap) ferme, rien n'est utilisé.

Le Nametag n'est retiré qu'à la réussite du renommage. Fermer le menu rend l'item posé. Les clients sans dialogues (avant 1.21.6, lus avec ViaVersion) écrivent le nom dans le chat après avoir validé l'item.

## Journal

Avec SHLogs, dans `plugins/SHLogs/SHItemTags/<mois>/` (sans SHLogs, la console) : `renommages` (ancien et nouveau nom, en clair et en codes &), `refus` (« Renommer » refusé pour un mot interdit ou une imitation), `dons` (`give` et `giveall`). Fichiers dans `config.yml` › `journal`, textes dans `messages.yml` › `journal`.

## Dépendances

- Obligatoires : `NBTAPI`
- Facultatives : `ViaVersion` (repérer les clients sans dialogues), `SHLogs` (journal)

## Commandes

Commande `/shitemtags`, utilisable depuis la console (c'est ainsi que SHCrates donne un tag).

| Commande | Description | Permission |
|---|---|---|
| `/shitemtags give <joueur> itemnametag [nombre]` | Donne des Nametags à un joueur. | `shitemtags.admin.give` |
| `/shitemtags giveall itemnametag [nombre]` | Donne des Nametags à tous les joueurs connectés. | `shitemtags.admin.giveall` |
| `/shitemtags reload` | Recharge `config.yml` et `messages.yml`. Une erreur de syntaxe annule le rechargement. | `shitemtags.admin.reload` |

Utiliser un Nametag (clic droit) ne demande aucune permission.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shitemtags.admin` | Toute l'administration (parent des permissions ci-dessous). | `op` |
| `shitemtags.admin.give` | `give`. | `op` |
| `shitemtags.admin.giveall` | `giveall`. | `op` |
| `shitemtags.admin.reload` | `reload`. | `op` |

## Configuration

Fichiers par défaut, avec des valeurs d'exemple. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHItemTags/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
