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

## Dépendances

- Obligatoires : `NBTAPI`

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

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

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
